import file from '@system.file'

export const BOOKS_DIRECTORY = 'internal://files/books/';

const BYTES_PER_CHAR = 2;
const DECODE_STEP = 1024;
const EXTENSION_PATTERN = /\.txt$/i;

export function isBookUri(uri) {
  return EXTENSION_PATTERN.test(uri);
}

export function bookDisplayName(uri) {
  return uri.split('/').pop().replace(EXTENSION_PATTERN, '');
}

export function measureBook(uri, onSuccess, onFail) {
  file.get({
    uri: uri,
    success: (meta) => onSuccess(Math.floor(meta.length / BYTES_PER_CHAR)),
    fail: onFail
  });
}

function decodeUtf16Le(bytes) {
  const units = bytes.length >> 1;
  let text = '';
  for (let start = 0; start < units; start += DECODE_STEP) {
    const end = Math.min(start + DECODE_STEP, units);
    const codes = new Array(end - start);
    for (let unit = start, slot = 0; unit < end; unit++, slot++) {
      codes[slot] = (bytes[unit * 2 + 1] << 8) | bytes[unit * 2];
    }
    text += String.fromCharCode.apply(null, codes);
  }
  return text;
}

export function readChars(uri, charOffset, charCount, onSuccess, onFail) {
  file.readArrayBuffer({
    uri: uri,
    position: charOffset * BYTES_PER_CHAR,
    length: charCount * BYTES_PER_CHAR,
    success: (result) => onSuccess(decodeUtf16Le(result.buffer)),
    fail: onFail
  });
}
