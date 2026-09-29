//! Yano's WebAssembly wrapper around Pragma's Amaru Conway transaction validator (ADR-057).
//!
//! The module is pure: it reads only the request bytes the host passes in and returns a response.
//! On `wasm32-wasip1` its only imports are WASI preview-1 functions (clock, random, fd_write for
//! panics, proc_exit) and it needs no file system, network or environment access.
//!
//! # Exports (interface version 1)
//!
//! | Export | Returns |
//! |---|---|
//! | `abi_version() -> u32` | [`ABI_VERSION`] |
//! | `amaru_version() -> ptr` | `[u32 LE len][UTF-8]`: [`VERSION_TEXT`], `AMARU_VERSION` plus a `crate=` line |
//! | `alloc(len) -> ptr` / `dealloc(ptr, len)` | guest memory for requests and responses |
//! | `required_keys(tx_ptr, tx_len, env_ptr, env_len) -> ptr` | `[u32 LE len][CBOR]` key set |
//! | `validate(req_ptr, req_len) -> ptr` | `[u32 LE len][CBOR]` verdict |
//!
//! Every returned pointer addresses a `[u32 LE len][payload]` buffer that the host frees with
//! `dealloc(ptr, 4 + len)`. The schemas are in `INTERFACE.md`.

pub mod engine;
pub mod failure;
pub mod interface;

pub use interface::{ABI_VERSION, Mode, Request, RequiredKeys, Response};

/// `tag=…`, `commit=…`, `toolchain=…` lines: the Amaru revision compiled into this module.
pub const AMARU_VERSION: &str = include_str!("../AMARU_VERSION");

/// This crate's version (`Cargo.toml`). It is bumped whenever the module's behaviour seen by the host changes
/// without an Amaru upgrade, for example the failure-name mapping in [`failure`], so that a stale module can be
/// told apart from the current one (Yano's conformance harness refuses a module whose crate version is not the
/// one in `Cargo.toml`).
pub const CRATE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// What `amaru_version` returns: the `AMARU_VERSION` lines, then `crate=<CRATE_VERSION>`.
pub const VERSION_TEXT: &str = concat!(include_str!("../AMARU_VERSION"), "crate=", env!("CARGO_PKG_VERSION"), "\n");

/// Copy `payload` into a fresh `[u32 LE len][payload]` buffer and leak it to the host.
fn into_host_buffer(payload: &[u8]) -> *mut u8 {
    let mut buffer = Vec::with_capacity(4 + payload.len());
    buffer.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    buffer.extend_from_slice(payload);
    let mut buffer = std::mem::ManuallyDrop::new(buffer.into_boxed_slice());
    buffer.as_mut_ptr()
}

/// # Safety
/// `ptr..ptr+len` must be a live allocation obtained from [`alloc`] (or null with `len == 0`).
unsafe fn host_slice<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if ptr.is_null() || len == 0 {
        return &[];
    }
    unsafe { std::slice::from_raw_parts(ptr, len) }
}

/// The interface version implemented by this module.
#[unsafe(no_mangle)]
pub extern "C" fn abi_version() -> u32 {
    ABI_VERSION
}

/// The Amaru revision this module was built from and this crate's version ([`VERSION_TEXT`]), as
/// `[u32 LE len][UTF-8]`.
#[unsafe(no_mangle)]
pub extern "C" fn amaru_version() -> *mut u8 {
    into_host_buffer(VERSION_TEXT.as_bytes())
}

/// Allocate `len` bytes of guest memory for the host to write a request into.
#[unsafe(no_mangle)]
pub extern "C" fn alloc(len: usize) -> *mut u8 {
    let mut buffer = std::mem::ManuallyDrop::new(vec![0u8; len].into_boxed_slice());
    buffer.as_mut_ptr()
}

/// Free a buffer returned by [`alloc`] (with the same `len`) or by an export (with `4 + len`).
///
/// # Safety
/// `ptr` must come from this module and `len` must be the exact size of that allocation.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        unsafe { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len))) };
    }
}

/// Run Amaru's `prepare_transaction` on the transaction and return the keys the host must
/// resolve, as `[u32 LE len][CBOR]`.
///
/// # Safety
/// Both ranges must be live allocations obtained from [`alloc`] (or null with length 0).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn required_keys(
    tx_ptr: *const u8,
    tx_len: usize,
    env_ptr: *const u8,
    env_len: usize,
) -> *mut u8 {
    let (transaction, env) = unsafe { (host_slice(tx_ptr, tx_len), host_slice(env_ptr, env_len)) };
    into_host_buffer(&engine::required_keys(transaction, env))
}

/// Validate the request at `req_ptr..req_ptr+req_len` and return the verdict as
/// `[u32 LE len][CBOR]`.
///
/// # Safety
/// The range must be a live allocation obtained from [`alloc`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn validate(req_ptr: *const u8, req_len: usize) -> *mut u8 {
    let request = unsafe { host_slice(req_ptr, req_len) };
    into_host_buffer(&engine::validate(request).to_cbor())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_host_buffer(ptr: *mut u8) -> Vec<u8> {
        unsafe {
            let len = u32::from_le_bytes(std::slice::from_raw_parts(ptr, 4).try_into().unwrap()) as usize;
            let payload = std::slice::from_raw_parts(ptr.add(4), len).to_vec();
            dealloc(ptr, 4 + len);
            payload
        }
    }

    #[test]
    fn amaru_version_round_trips_through_host_buffer() {
        let text = String::from_utf8(read_host_buffer(amaru_version())).unwrap();
        assert!(text.starts_with("tag=v"), "{text}");
        assert!(text.ends_with(&format!("crate={CRATE_VERSION}\n")), "{text}");
        assert_eq!(abi_version(), 1);
    }

    #[test]
    fn malformed_request_is_an_error_not_a_verdict() {
        let ptr = alloc(3);
        unsafe {
            std::ptr::copy_nonoverlapping([0xa1u8, 0x00, 0x02].as_ptr(), ptr, 3);
            let response = read_host_buffer(validate(ptr, 3));
            dealloc(ptr, 3);
            assert!(matches!(Response::from_cbor(&response).unwrap(), Response::Error(_)));
        }
    }
}
