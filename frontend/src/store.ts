import { configureStore, createListenerMiddleware, createSlice } from '@reduxjs/toolkit';
import type { PayloadAction } from '@reduxjs/toolkit';
import { rememberLanguage } from './language';
import type { Language } from './language';
import { sameOwner } from './authBoundary';
import type { Owner } from './authBoundary';
import type { Notice, Outcome, Phase } from './authentication';

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

export type AuthenticationState = Outcome & { owner: Owner | null };
const initialAuthentication: AuthenticationState = { owner: null, phase: 'CHECKING', notice: 'NONE', user: null };
const authenticationSlice = createSlice({
  name: 'authentication',
  initialState: initialAuthentication,
  reducers: {
    authenticationStarted(_state, action: PayloadAction<{ owner: Owner; phase: Phase; notice: Notice }>) {
      return { ...action.payload, user: null };
    },
    authenticationFinished(state, action: PayloadAction<{ owner: Owner; result: Outcome }>) {
      if (!sameOwner(state.owner, action.payload.owner)) return;
      const result = action.payload.result;
      return { owner: action.payload.owner, ...result, user: result.phase === 'AUTHENTICATED' ? result.user : null };
    },
  },
});
export const { authenticationStarted, authenticationFinished } = authenticationSlice.actions;

export function createAppStore(language: Language, persist = rememberLanguage) {
  const preferenceListener = createListenerMiddleware();
  preferenceListener.startListening({
    actionCreator: languageSelected,
    effect: action => { persist(action.payload); },
  });
  return configureStore({
    reducer: { ui: uiSlice.reducer, authentication: authenticationSlice.reducer },
    preloadedState: { ui: { language } },
    middleware: getDefault => getDefault().prepend(preferenceListener.middleware),
  });
}

export type AppStore = ReturnType<typeof createAppStore>;
export type RootState = ReturnType<AppStore['getState']>;
export type AppDispatch = AppStore['dispatch'];
