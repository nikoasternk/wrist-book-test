const LEADING_SPACE = /^\s+/;
const TRAILING_SPACE = /\s+$/;
const FORWARD_CUT_FLOOR = 0.4;
const BACKWARD_SKIP_CEILING = 0.6;
const GLYPH_WIDTH_EM = 0.56;
const MIN_SEGMENT_CHARS = 200;
const MAX_SEGMENT_CHARS = 800;
const SEGMENT_SCREENS = 4;

function isHighSurrogate(text, index) {
  const code = text.charCodeAt(index);
  return code >= 0xD800 && code <= 0xDBFF;
}

function isLowSurrogate(text, index) {
  const code = text.charCodeAt(index);
  return code >= 0xDC00 && code <= 0xDFFF;
}

function safeEnd(text, end) {
  return end > 0 && isHighSurrogate(text, end - 1) ? end - 1 : end;
}

export function charsPerLineFor(fontSize, contentWidth) {
  return Math.max(4, Math.floor(contentWidth / (fontSize * GLYPH_WIDTH_EM)));
}

export function maxSegmentCharsFor(charsPerLine, lineHeight, viewportHeight) {
  const linesPerSegment = (SEGMENT_SCREENS * viewportHeight) / lineHeight;
  const chars = Math.floor(charsPerLine * linesPerSegment);
  return Math.max(MIN_SEGMENT_CHARS, Math.min(MAX_SEGMENT_CHARS, chars));
}

export function findForwardCut(text) {
  const floor = Math.floor(text.length * FORWARD_CUT_FLOOR);
  let cut = text.lastIndexOf('\n');
  if (cut < floor) {
    cut = text.lastIndexOf(' ');
  }
  if (cut < floor) {
    return safeEnd(text, text.length);
  }
  return cut + 1;
}

export function findBackwardSkip(text, limit) {
  const ceiling = Math.min(Math.floor(text.length * BACKWARD_SKIP_CEILING), limit);
  let cut = text.indexOf('\n');
  if (cut === -1 || cut > ceiling) {
    cut = text.indexOf(' ');
  }
  if (cut === -1 || cut > ceiling) {
    return text.length > 0 && isLowSurrogate(text, 0) ? 1 : 0;
  }
  return cut + 1;
}

function findSegmentCut(raw, cursor, maxChars) {
  const limit = cursor + maxChars;
  const space = raw.lastIndexOf(' ', limit);
  if (space > cursor + (maxChars >> 1)) {
    return space + 1;
  }
  return safeEnd(raw, limit);
}

function pushSegment(segments, raw, from, to, baseOffset, gap) {
  const text = raw.slice(from, to).replace(LEADING_SPACE, '').replace(TRAILING_SPACE, '');
  if (!text) {
    return;
  }
  const start = baseOffset + from;
  segments.push({ key: start + 1, start: start, span: to - from, text: text, gap: gap });
}

export function segmentize(raw, baseOffset, maxChars, paragraphGap) {
  const segments = [];
  const length = raw.length;
  let position = 0;
  while (position < length) {
    const newline = raw.indexOf('\n', position);
    const closed = newline !== -1;
    const paragraphEnd = closed ? newline + 1 : length;
    let cursor = position;
    while (paragraphEnd - cursor > maxChars) {
      const cut = findSegmentCut(raw, cursor, maxChars);
      pushSegment(segments, raw, cursor, cut, baseOffset, 0);
      cursor = cut;
    }
    pushSegment(segments, raw, cursor, paragraphEnd, baseOffset, closed ? paragraphGap : 0);
    position = paragraphEnd;
  }
  return segments;
}

export function estimateSegmentHeight(segment, charsPerLine, lineHeight) {
  const lines = Math.max(1, Math.ceil(segment.text.length / charsPerLine));
  return lines * lineHeight + segment.gap;
}
