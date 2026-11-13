import { createContext } from 'react';
import type { AccountCartAdmission } from './accountCartAdmission';

export const AccountPurchaseContext=createContext<AccountCartAdmission|null>(null);
