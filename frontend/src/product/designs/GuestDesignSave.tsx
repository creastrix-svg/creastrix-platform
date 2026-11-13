import {useEffect,useRef,useState} from 'react';
import {useNavigate} from 'react-router';
import {useProductLocale} from '../useProductLocale';
import {designLibraryCopy} from './designLibraryCopy';
import {guestDesignPromptCopy} from './guestDesignPromptCopy';
import Modal from '../Modal';
import {holdGuestDesign} from './guestDesignHandoff';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';

export default function GuestDesignSave({enabled,sku,snapshot}:{enabled:boolean;sku:string;snapshot:()=>AppliedLoopDesign}){
  const locale=useProductLocale(),t=designLibraryCopy[locale],prompt=guestDesignPromptCopy[locale];
  const navigate=useNavigate(),alive=useRef(false),lock=useRef(false),dismissed=useRef(false);
  const [open,setOpen]=useState(false),[phase,set]=useState<'ready'|'saving'|'failed'>('ready');
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  function close(){dismissed.current=true;setOpen(false);}
  async function start(){
    if(!alive.current||!enabled||lock.current)return;
    lock.current=true;set('saving');
    try{const design=snapshot();if(design.sku!==sku)throw Error('DESIGN_CHANGED');
      const draft=await holdGuestDesign(design);
      if(alive.current&&!dismissed.current)navigate('/login?design='+draft);
    }catch{if(alive.current&&!dismissed.current)set('failed');}
    finally{lock.current=false;if(alive.current&&dismissed.current)set('ready');}
  }
  return <div className="studio-library-actions"><button className="product-secondary" type="button"
    disabled={!enabled||phase==='saving'} aria-haspopup="dialog"
    onClick={()=>{dismissed.current=false;set('ready');setOpen(true);}}>{t.save}</button>
    {open&&<Modal title={t.save} closeLabel={t.cancel} onClose={close} className="guest-design-dialog">
      <p>{prompt.message}</p>
      <div className="guest-design-choices">
        <button type="button" className="product-primary" disabled={!enabled} aria-disabled={phase==='saving'||!enabled}
          onClick={()=>void start()}>{t.signIn}</button>
        <button type="button" className="product-secondary" disabled aria-describedby="design-signup-note">{prompt.register}</button>
      </div>
      <p id="design-signup-note" className="guest-design-note">{prompt.localOnly}</p>
      {phase==='failed'&&<p role="alert">{t.failed}</p>}
      {phase==='saving'&&<p role="status">{t.saving}</p>}
    </Modal>}</div>;
}
