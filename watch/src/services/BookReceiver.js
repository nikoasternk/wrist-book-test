import file from '@system.file'
import { BOOKS_DIRECTORY } from './BookFile'

const PART_SUFFIX = '.part';
const MAX_STEM_LENGTH = 80;
const FORBIDDEN_NAME_CHARS = /[\\\/:*?"<>|+,;=\[\]\u0000-\u001F\u007F]/g;
const EXTENSION_PATTERN = /\.txt$/i;

function encodeUtf16Le(text) {
  const bytes = new Uint8Array(text.length * 2);
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    bytes[i * 2] = code & 0xFF;
    bytes[i * 2 + 1] = code >> 8;
  }
  return bytes;
}

function sanitizeFileName(name) {
  const stem = String(name || '')
    .replace(EXTENSION_PATTERN, '')
    .replace(FORBIDDEN_NAME_CHARS, '_')
    .replace(/^\.+/, '')
    .trim()
    .slice(0, MAX_STEM_LENGTH);
  return (stem || 'book') + '.txt';
}

export default class BookReceiver {
  constructor(bridge) {
    this.bridge = bridge;
    this.transferListener = null;
    this.targetName = '';
    this.finalUri = '';
    this.partUri = '';
    this.totalChunks = 0;
    this.expectedIndex = 0;
    this.active = false;

    this.bridge.registerHandler('book', (packet) => this.handlePacket(packet));
  }

  setTransferListener(callback) {
    this.transferListener = callback;
  }

  notify(event) {
    if (this.transferListener) {
      this.transferListener(event);
    }
  }

  reply(status, extra) {
    this.bridge.sendPacket(Object.assign({ tag: 'book', status: status }, extra || {}));
  }

  handlePacket(packet) {
    if (packet.action === 'start') {
      this.beginTransfer(packet);
    } else if (packet.action === 'chunk') {
      this.receiveChunk(packet);
    }
  }

  beginTransfer(packet) {
    this.active = false;
    this.targetName = sanitizeFileName(packet.fileName);
    this.finalUri = BOOKS_DIRECTORY + this.targetName;
    this.partUri = this.finalUri + PART_SUFFIX;
    this.totalChunks = Math.max(0, Number(packet.totalChunks) || 0);
    this.expectedIndex = 0;

    file.mkdir({
      uri: BOOKS_DIRECTORY,
      success: () => this.resetPartFile(),
      fail: () => this.resetPartFile()
    });
  }

  resetPartFile() {
    file.delete({
      uri: this.partUri,
      complete: () => {
        this.active = true;
        this.reply('ready');
      }
    });
  }

  receiveChunk(packet) {
    if (!this.active || packet.index !== this.expectedIndex || typeof packet.payload !== 'string') {
      this.reply('error', { expectedIndex: this.expectedIndex });
      return;
    }

    const index = packet.index;
    file.writeArrayBuffer({
      uri: this.partUri,
      buffer: encodeUtf16Le(packet.payload),
      append: index > 0,
      success: () => {
        this.expectedIndex = index + 1;
        this.notify({
          state: 'progress',
          fileName: this.targetName,
          progress: this.totalChunks > 0 ? index / this.totalChunks : 1
        });
        if (index >= this.totalChunks) {
          this.finalize(index);
        } else {
          this.reply('next', { index: this.expectedIndex });
        }
      },
      fail: () => this.abort(index)
    });
  }

  finalize(index) {
    file.delete({
      uri: this.finalUri,
      complete: () => {
        file.move({
          srcUri: this.partUri,
          dstUri: this.finalUri,
          success: () => {
            this.active = false;
            this.reply('completed', { index: index });
            this.notify({ state: 'completed' });
          },
          fail: () => this.abort(index)
        });
      }
    });
  }

  abort(index) {
    this.active = false;
    file.delete({ uri: this.partUri });
    this.reply('error', { index: index });
    this.notify({ state: 'error' });
  }
}
