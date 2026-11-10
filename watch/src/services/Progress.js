import storage from '@system.storage'

const PROGRESS_KEY = 'WRISTBOOK_PROGRESS';

const queue = [];
let draining = false;

function parseMap(raw) {
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

function readMap(callback) {
  storage.get({
    key: PROGRESS_KEY,
    success: (data) => callback(parseMap(data)),
    fail: () => callback({})
  });
}

function drain() {
  const task = queue.shift();
  if (!task) {
    draining = false;
    return;
  }
  draining = true;
  task(drain);
}

function enqueue(task) {
  queue.push(task);
  if (!draining) {
    drain();
  }
}

function mutate(change) {
  enqueue((done) => {
    readMap((map) => {
      change(map);
      storage.set({
        key: PROGRESS_KEY,
        value: JSON.stringify(map),
        complete: done
      });
    });
  });
}

export function loadProgressMap(callback) {
  readMap(callback);
}

export function saveProgress(name, entry) {
  mutate((map) => {
    map[name] = entry;
  });
}

export function removeProgress(name) {
  mutate((map) => {
    delete map[name];
  });
}

export function readOffset(entry) {
  if (!entry) {
    return 0;
  }
  if (typeof entry.offset === 'number') {
    return Math.max(0, entry.offset);
  }
  if (typeof entry.charOffset === 'number') {
    return Math.max(0, entry.charOffset);
  }
  return 0;
}

export function readPercent(entry) {
  if (!entry || typeof entry.percent !== 'number') {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round(entry.percent)));
}
