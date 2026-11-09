import { useId } from 'react';
import { Icon } from './Icon';
import { studioExtensionCopy } from './studioExtensionCopy';
import { useProductLocale } from './useProductLocale';

export default function StudioIdeaTools({ prompt, onPrompt }: { prompt: string; onPrompt: (prompt: string) => void }) {
  const e = studioExtensionCopy[useProductLocale()], id = useId();
  return <div className="studio-idea-tools">
    <label htmlFor={id}>{e.aiPrompt}</label>
    <div className="studio-idea-input"><textarea id={id} value={prompt} maxLength={600} placeholder={e.aiPlaceholder} rows={4} aria-describedby={id + '-availability'} onChange={event => onPrompt(event.target.value.slice(0, 600))} /><span>{prompt.length}/600</span></div>
    <p id={id + '-availability'} className="studio-ai-boundary"><Icon name="spark" />{e.aiUnavailable}</p>
  </div>;
}
