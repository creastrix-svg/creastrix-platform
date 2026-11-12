import { createContext } from 'react';

// The normal storefront owns a guest cart. Supplying an account reader must
// never silently redirect a purchase or an old cart-line editor to that cart.
export const GuestCartActionsEnabled = createContext(true);
