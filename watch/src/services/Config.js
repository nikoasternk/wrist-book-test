import storage from '@system.storage'

const CONFIG_KEY = 'WRISTBOOK_CONFIG';

export const DEFAULT_CONFIG = {
  language: 'auto',
  fontSize: 30,
  paddingY: 38,
  stayAwake: true,
  brightnessPct: 100,
  bufferChars: 1500
};

export const CONFIG_LIMITS = {
  fontSize: { min: 20, max: 48 },
  paddingY: { min: 16, max: 76 },
  brightnessPct: { min: 10, max: 100 },
  bufferChars: { min: 500, max: 6000 }
};

function clampInt(value, limits, fallback) {
  if (value === undefined || value === null) {
    return fallback;
  }
  const number = Math.round(Number(value));
  if (!isFinite(number)) {
    return fallback;
  }
  return Math.min(limits.max, Math.max(limits.min, number));
}

function parseObject(raw) {
  if (!raw) {
    return {};
  }
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    return {};
  }
}

export function normalizeConfig(raw) {
  const source = parseObject(raw);
  return {
    language: source.language === 'en' || source.language === 'ru' ? source.language : 'auto',
    fontSize: clampInt(source.fontSize, CONFIG_LIMITS.fontSize, DEFAULT_CONFIG.fontSize),
    paddingY: clampInt(source.paddingY, CONFIG_LIMITS.paddingY, DEFAULT_CONFIG.paddingY),
    stayAwake: source.stayAwake === undefined ? DEFAULT_CONFIG.stayAwake : Boolean(source.stayAwake),
    brightnessPct: clampInt(source.brightnessPct, CONFIG_LIMITS.brightnessPct, DEFAULT_CONFIG.brightnessPct),
    bufferChars: clampInt(source.bufferChars, CONFIG_LIMITS.bufferChars, DEFAULT_CONFIG.bufferChars)
  };
}

export function loadConfig(callback) {
  storage.get({
    key: CONFIG_KEY,
    success: (data) => callback(normalizeConfig(data)),
    fail: () => callback(normalizeConfig(null))
  });
}

export function saveConfig(config) {
  storage.set({
    key: CONFIG_KEY,
    value: JSON.stringify(normalizeConfig(config))
  });
}

export function hardwareBrightness(percent) {
  return Math.max(10, Math.min(255, Math.round(percent * 2.55)));
}

export function textColorFor(percent) {
  const level = Math.max(0, Math.min(255, Math.round(percent * 2.55)));
  const hex = level.toString(16);
  const channel = hex.length === 1 ? '0' + hex : hex;
  return '#' + channel + channel + channel;
}
