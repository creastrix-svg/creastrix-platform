import { useAppSelector } from '../hooks';

// Extra storefront locales never expand or rewrite the authentication DE/EN preference.
export function useProductLocale() { return useAppSelector(state => state.productDemo.locale ?? state.ui.language); }
