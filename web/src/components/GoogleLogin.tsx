import { useEffect, useRef, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { MeOut } from '../api/types';
import { Notice } from './ui';

interface GoogleIdentity {
  initialize(options: { client_id: string; nonce: string; callback: (result: { credential: string }) => void }): void;
  renderButton(element: HTMLElement, options: { theme: string; size: string; text: string; locale: string }): void;
}

let loading: Promise<GoogleIdentity> | undefined;

function loadIdentity(): Promise<GoogleIdentity> {
  return loading ??= new Promise<GoogleIdentity>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => {
      const sdk = (window as Window & { google?: { accounts: { id: GoogleIdentity } } }).google?.accounts.id;
      if (sdk) resolve(sdk);
      else reject(new Error('Не удалось загрузить вход через Google'));
    };
    script.onerror = () => reject(new Error('Не удалось загрузить вход через Google'));
    document.head.appendChild(script);
  }).catch((err) => {
    loading = undefined;
    throw err;
  });
}

export function GoogleLogin({ clientId, onLogin }: { clientId: string; onLogin: (me: MeOut) => void }) {
  const target = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string>();
  const [generation, retry] = useState(0);

  useEffect(() => {
    const ctl = new AbortController();
    Promise.all([loadIdentity(), api.googleNonce(ctl.signal)]).then(([sdk, { nonce }]) => {
      const element = target.current;
      if (!element || ctl.signal.aborted) return;
      sdk.initialize({ client_id: clientId, nonce, callback: ({ credential }) => {
        if (ctl.signal.aborted) return;
        void api.googleWeb(credential).then(onLogin, (err) => setError(errorMessage(err)));
      } });
      element.replaceChildren();
      sdk.renderButton(element, { theme: 'filled_black', size: 'large', text: 'continue_with', locale: 'ru' });
    }).catch((err) => {
      if (!ctl.signal.aborted) setError(errorMessage(err));
    });
    return () => ctl.abort();
  }, [clientId, generation, onLogin]);

  return (
    <div className="mt-3 flex flex-col items-center gap-2">
      <div ref={target} />
      {error && <>
        <Notice kind="error">{error}</Notice>
        <button type="button" className="min-h-11 text-sm font-semibold text-accent" onClick={() => {
          setError(undefined);
          retry((value) => value + 1);
        }}>Повторить вход через Google</button>
      </>}
    </div>
  );
}
