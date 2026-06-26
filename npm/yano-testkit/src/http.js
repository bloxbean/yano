// @ts-check

/**
 * @typedef YanoHttpRequestOptions
 * @property {AbortSignal=} signal
 * @property {number=} timeoutMs
 * @property {Record<string, string>=} headers
 *
 * @typedef YanoHttpClient
 * @property {string} apiBaseUrl
 * @property {(path: string) => URL} url
 * @property {<T = unknown>(path: string, options?: YanoHttpRequestOptions) => Promise<T>} getJson
 * @property {<T = unknown>(path: string, body?: unknown, options?: YanoHttpRequestOptions) => Promise<T>} postJson
 * @property {<T = unknown>(path: string, options?: YanoHttpRequestOptions) => Promise<T>} deleteJson
 * @property {<T = unknown>(path: string, body: BodyInit, options?: YanoHttpRequestOptions) => Promise<T>} postCbor
 * @property {<T = unknown>(path: string, body: string, options?: YanoHttpRequestOptions) => Promise<T>} postText
 * @property {(path: string, options?: YanoHttpRequestOptions) => Promise<Uint8Array>} getBytes
 */

export class YanoHttpError extends Error {
  /**
   * @param {string} message
   * @param {{ method: string, url: string, status: number, statusText: string, bodyText: string }} details
   */
  constructor(message, details) {
    super(message);
    this.name = "YanoHttpError";
    /** @type {string} */
    this.method = details.method;
    /** @type {string} */
    this.url = details.url;
    /** @type {number} */
    this.status = details.status;
    /** @type {string} */
    this.statusText = details.statusText;
    /** @type {string} */
    this.bodyText = details.bodyText;
  }
}

/**
 * @param {string} apiBaseUrl
 * @returns {YanoHttpClient}
 */
export function createYanoHttpClient(apiBaseUrl) {
  const base = apiBaseUrl.endsWith("/") ? apiBaseUrl : `${apiBaseUrl}/`;

  /**
   * @param {string} path
   * @param {string} contentType
   * @param {BodyInit | undefined} body
   * @param {YanoHttpRequestOptions} options
   */
  const postBody = (path, contentType, body, options) =>
    requestJson(base, "POST", path, body, {
      ...options,
      headers: { "content-type": contentType, ...(options.headers ?? {}) }
    });

  return {
    apiBaseUrl: base,
    url: (path) => new URL(trimLeadingSlash(path), base),
    getJson: (path, options = {}) => requestJson(base, "GET", path, undefined, options),
    postJson: (path, body, options = {}) =>
      postBody(path, "application/json", body === undefined ? undefined : JSON.stringify(body), options),
    deleteJson: (path, options = {}) => requestJson(base, "DELETE", path, undefined, options),
    postCbor: (path, body, options = {}) => postBody(path, "application/cbor", body, options),
    postText: (path, body, options = {}) => postBody(path, "text/plain", body, options),
    getBytes: (path, options = {}) => requestBytes(base, "GET", path, options)
  };
}

/**
 * @param {string} base
 * @param {string} method
 * @param {string} path
 * @param {BodyInit | undefined} body
 * @param {YanoHttpRequestOptions} options
 */
async function requestJson(base, method, path, body, options) {
  const response = await request(base, method, path, body, options);
  return parseResponseBody(response);
}

/**
 * @param {string} base
 * @param {string} method
 * @param {string} path
 * @param {YanoHttpRequestOptions} options
 */
async function requestBytes(base, method, path, options) {
  const response = await request(base, method, path, undefined, options);
  return new Uint8Array(await response.arrayBuffer());
}

/**
 * @param {string} base
 * @param {string} method
 * @param {string} path
 * @param {BodyInit | undefined} body
 * @param {YanoHttpRequestOptions} options
 */
async function request(base, method, path, body, options) {
  const url = new URL(trimLeadingSlash(path), base);
  const response = await fetch(url, {
    method,
    headers: options.headers,
    body,
    signal: options.signal ?? timeoutSignal(options.timeoutMs)
  });
  if (!response.ok) {
    const bodyText = await response.text();
    throw new YanoHttpError(
      `${method} ${url} failed with ${response.status}: ${bodyText}`,
      {
        method,
        url: url.toString(),
        status: response.status,
        statusText: response.statusText,
        bodyText
      }
    );
  }
  return response;
}

/**
 * @param {Response} response
 */
async function parseResponseBody(response) {
  const bodyText = await response.text();
  if (!bodyText) {
    return null;
  }
  try {
    return JSON.parse(bodyText);
  } catch {
    return bodyText;
  }
}

/**
 * @param {number | undefined} timeoutMs
 */
function timeoutSignal(timeoutMs) {
  return timeoutMs && timeoutMs > 0 ? AbortSignal.timeout(timeoutMs) : undefined;
}

/**
 * @param {string} path
 */
function trimLeadingSlash(path) {
  return path.replace(/^\/+/, "");
}
