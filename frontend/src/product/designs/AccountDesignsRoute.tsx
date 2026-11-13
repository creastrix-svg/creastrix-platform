import {useState} from 'react';
import {Navigate} from 'react-router';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import MyDesignsView from './MyDesignsView';
import GuestDesignImport from './GuestDesignImport';

export default function AccountDesignsRoute({admission}:{admission?:AccountCartAdmission}){
  const [refresh,setRefresh]=useState(0);
  if(!admission)return <Navigate to="/login" replace/>;
  return <MyDesignsView key={refresh} admission={admission}
    extra={<GuestDesignImport admission={admission} onSaved={()=>setRefresh(n=>n+1)}/>}/>;
}
