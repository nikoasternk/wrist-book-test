import configuration from '@system.configuration'

const DICTIONARY = {
  en: {
    appName: 'Wrist Book',
    settings: 'Settings',
    emptyTitle: 'No Books Yet',
    emptySubtitle: 'Send text or a TXT file from your phone',
    backToShelf: '← Bookshelf',
    deleteBook: 'Delete Book',
    confirmDelete: 'Confirm Delete?',
    closeMenu: 'Close Menu',
    fileError: 'File Error',
    emptyFile: 'Empty File',
    readError: 'Read Error',
    back: '← Back',
    language: 'Language',
    langAuto: 'Auto',
    langEn: 'English',
    langRu: 'Русский',
    keepScreenOn: 'Keep Screen On',
    enabled: 'ENABLED',
    disabled: 'DISABLED',
    brightness: 'Brightness',
    sampleText: 'Sample Text',
    fontSize: 'Font Size',
    bufferSize: 'Buffer (chars)',
    edgePadding: 'Edge Padding',
    syncTitle: 'Syncing',
    statusWaiting: 'Waiting...',
    statusConnected: 'Connected',
    statusReceiving: 'Receiving...',
    statusCompleted: 'Completed!',
    statusError: 'Write Error'
  },
  ru: {
    appName: 'Wrist Book',
    settings: 'Настройки',
    emptyTitle: 'Полка пуста',
    emptySubtitle: 'Отправьте текст или файл с телефона',
    backToShelf: '← На полку',
    deleteBook: 'Удалить',
    confirmDelete: 'Точно удалить?',
    closeMenu: 'Закрыть',
    fileError: 'Ошибка файла',
    emptyFile: 'Пустой файл',
    readError: 'Ошибка чтения',
    back: '← Назад',
    language: 'Язык',
    langAuto: 'Авто',
    langEn: 'English',
    langRu: 'Русский',
    keepScreenOn: 'Не гасить экран',
    enabled: 'ВКЛЮЧЕНО',
    disabled: 'ВЫКЛЮЧЕНО',
    brightness: 'Яркость',
    sampleText: 'Пример текста',
    fontSize: 'Размер шрифта',
    bufferSize: 'Буфер (симв.)',
    edgePadding: 'Отступы краев',
    syncTitle: 'Синхронизация',
    statusWaiting: 'Ожидание...',
    statusConnected: 'Связь ОК',
    statusReceiving: 'Прием...',
    statusCompleted: 'Готово!',
    statusError: 'Ошибка записи'
  }
};

export function resolveLanguageCode(preferredMode) {
  if (preferredMode === 'en' || preferredMode === 'ru') {
    return preferredMode;
  }
  try {
    const locale = configuration.getLocale();
    if (locale && locale.language && locale.language.toLowerCase().indexOf('ru') === 0) {
      return 'ru';
    }
  } catch (e) {}
  return 'en';
}

export function getStrings(preferredMode) {
  const code = resolveLanguageCode(preferredMode || 'auto');
  return DICTIONARY[code] || DICTIONARY.en;
}
