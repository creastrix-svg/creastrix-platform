import { configureStore, createListenerMiddleware, createSlice } from '@reduxjs/toolkit';
import type { PayloadAction } from '@reduxjs/toolkit';
import { rememberLanguage } from './language';
import type { Language } from './language';

const uiSlice = createSlice({
  name: 'ui',
  initialState: { language: 'en' as Language },
  reducers: {
    languageSelected(state, action: PayloadAction<Language>) {
      state.language = action.payload;
    },
  },
});

export const { languageSelected } = uiSlice.actions;

export function createAppStore(language: Language, persist = rememberLanguage) {
  const preferenceListener = createListenerMiddleware();
  preferenceListener.startListening({
    actionCreator: languageSelected,
    effect: action => { persist(action.payload); },
  });
  return configureStore({
    reducer: { ui: uiSlice.reducer },
    preloadedState: { ui: { language } },
    middleware: getDefault => getDefault().prepend(preferenceListener.middleware),
  });
}

export type AppStore = ReturnType<typeof createAppStore>;
export type RootState = ReturnType<AppStore['getState']>;
export type AppDispatch = AppStore['dispatch'];
