#!/usr/bin/env python3
"""
Small TCP proxy for Yano peer-recovery validation.

It forwards one local TCP port to an upstream relay and exposes a minimal HTTP
control port for fault injection:

  GET  /status
  POST /drop
  POST /blackhole?seconds=30
  POST /resume

The implementation intentionally uses only the Python standard library so it
can run on a developer machine or CI host without extra setup.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
import time
from dataclasses import dataclass
from typing import Set
from urllib.parse import parse_qs, urlparse


LOG = logging.getLogger("tcp-fault-proxy")


@dataclass(frozen=True)
class Connection:
    client: asyncio.StreamWriter
    upstream: asyncio.StreamWriter
    created_at: float


class FaultProxy:
    def __init__(
        self,
        listen_host: str,
        listen_port: int,
        target_host: str,
        target_port: int,
        control_host: str,
        control_port: int,
    ) -> None:
        self.listen_host = listen_host
        self.listen_port = listen_port
        self.target_host = target_host
        self.target_port = target_port
        self.control_host = control_host
        self.control_port = control_port
        self.connections: Set[Connection] = set()
        self.blackhole_until = 0.0
        self.accepted_connections = 0
        self.dropped_connections = 0
        self.bytes_forwarded = 0
        self.bytes_discarded = 0

    async def start(self) -> None:
        proxy_server = await asyncio.start_server(
            self.handle_proxy_client,
            self.listen_host,
            self.listen_port,
        )
        control_server = await asyncio.start_server(
            self.handle_control_client,
            self.control_host,
            self.control_port,
        )

        LOG.info(
            "Proxy listening on %s:%s -> %s:%s",
            self.listen_host,
            self.listen_port,
            self.target_host,
            self.target_port,
        )
        LOG.info("Control listening on http://%s:%s", self.control_host, self.control_port)

        async with proxy_server, control_server:
            await asyncio.gather(
                proxy_server.serve_forever(),
                control_server.serve_forever(),
            )

    async def handle_proxy_client(
        self,
        client_reader: asyncio.StreamReader,
        client_writer: asyncio.StreamWriter,
    ) -> None:
        peer = client_writer.get_extra_info("peername")
        self.accepted_connections += 1
        LOG.info("Accepted client connection from %s", peer)

        try:
            upstream_reader, upstream_writer = await asyncio.open_connection(
                self.target_host,
                self.target_port,
            )
        except Exception:
            LOG.exception("Failed to connect upstream %s:%s", self.target_host, self.target_port)
            self.close_writer(client_writer)
            return

        connection = Connection(client_writer, upstream_writer, time.time())
        self.connections.add(connection)
        try:
            await asyncio.gather(
                self.pipe(client_reader, upstream_writer, "client->upstream"),
                self.pipe(upstream_reader, client_writer, "upstream->client"),
            )
        finally:
            self.connections.discard(connection)
            self.close_writer(client_writer)
            self.close_writer(upstream_writer)
            LOG.info("Closed client connection from %s", peer)

    async def pipe(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        direction: str,
    ) -> None:
        try:
            while not reader.at_eof():
                data = await reader.read(65536)
                if not data:
                    break

                if self.is_blackholed():
                    self.bytes_discarded += len(data)
                    continue

                writer.write(data)
                await writer.drain()
                self.bytes_forwarded += len(data)
        except Exception as exc:
            LOG.info("Pipe closed for %s: %s", direction, exc)
        finally:
            self.close_writer(writer)

    async def handle_control_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            request_line = await asyncio.wait_for(reader.readline(), timeout=5)
            if not request_line:
                return

            parts = request_line.decode("ascii", errors="replace").strip().split()
            if len(parts) < 2:
                await self.respond(writer, 400, {"error": "bad request"})
                return

            method, raw_path = parts[0].upper(), parts[1]
            while True:
                line = await reader.readline()
                if not line or line == b"\r\n":
                    break

            parsed = urlparse(raw_path)
            if parsed.path == "/status":
                await self.respond(writer, 200, self.status())
            elif parsed.path == "/drop" and method in {"GET", "POST"}:
                count = self.drop_connections()
                await self.respond(writer, 200, {"dropped": count, **self.status()})
            elif parsed.path == "/blackhole" and method in {"GET", "POST"}:
                seconds = self.parse_seconds(parsed.query)
                self.blackhole_until = max(self.blackhole_until, time.monotonic() + seconds)
                await self.respond(writer, 200, {"blackholeSeconds": seconds, **self.status()})
            elif parsed.path == "/resume" and method in {"GET", "POST"}:
                self.blackhole_until = 0.0
                await self.respond(writer, 200, self.status())
            else:
                await self.respond(writer, 404, {"error": "not found"})
        except Exception as exc:
            LOG.info("Control request failed: %s", exc)
        finally:
            self.close_writer(writer)

    def parse_seconds(self, query: str) -> int:
        raw = parse_qs(query).get("seconds", ["30"])[0]
        try:
            seconds = int(raw)
        except ValueError:
            seconds = 30
        return max(1, min(seconds, 3600))

    def drop_connections(self) -> int:
        connections = list(self.connections)
        for connection in connections:
            self.close_writer(connection.client)
            self.close_writer(connection.upstream)
        self.dropped_connections += len(connections)
        LOG.warning("Dropped %s active connection(s)", len(connections))
        return len(connections)

    def is_blackholed(self) -> bool:
        return time.monotonic() < self.blackhole_until

    def status(self) -> dict:
        remaining = max(0.0, self.blackhole_until - time.monotonic())
        return {
            "listen": f"{self.listen_host}:{self.listen_port}",
            "target": f"{self.target_host}:{self.target_port}",
            "activeConnections": len(self.connections),
            "acceptedConnections": self.accepted_connections,
            "droppedConnections": self.dropped_connections,
            "blackholeRemainingSeconds": round(remaining, 3),
            "bytesForwarded": self.bytes_forwarded,
            "bytesDiscarded": self.bytes_discarded,
        }

    async def respond(self, writer: asyncio.StreamWriter, status: int, body: dict) -> None:
        payload = json.dumps(body, sort_keys=True).encode("utf-8")
        reason = "OK" if status == 200 else "ERROR"
        writer.write(
            f"HTTP/1.1 {status} {reason}\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {len(payload)}\r\n"
            "Connection: close\r\n"
            "\r\n"
            .encode("ascii")
        )
        writer.write(payload)
        await writer.drain()

    def close_writer(self, writer: asyncio.StreamWriter) -> None:
        if writer.is_closing():
            return
        writer.close()


async def run(args: argparse.Namespace) -> None:
    proxy = FaultProxy(
        args.listen_host,
        args.listen_port,
        args.target_host,
        args.target_port,
        args.control_host,
        args.control_port,
    )

    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    task = asyncio.create_task(proxy.start())
    await stop.wait()
    LOG.info("Stopping proxy")
    proxy.drop_connections()
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description="TCP proxy with fault injection controls")
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--target-host", required=True)
    parser.add_argument("--target-port", type=int, required=True)
    parser.add_argument("--control-host", default="127.0.0.1")
    parser.add_argument("--control-port", type=int, required=True)
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-5s %(name)s - %(message)s",
    )
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
