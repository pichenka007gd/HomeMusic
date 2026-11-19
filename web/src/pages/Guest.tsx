import { useState, type FormEvent } from 'react';
import { errorMessage, request, withQuery } from '../api/client';
import type { GuestTrack } from '../api/types';
import { Button, Notice } from '../components/ui';
import { useAsync } from '../lib/hooks';
import { Link } from '../lib/router';
import { LibraryCount } from './Home';

export function GuestPage() {
  const home = useAsync((signal) => request<{ track_count: number | null }>('GET', '/api/guest/home', { signal }), []);
  const [query, setQuery] = useState('');
  const [tracks, setTracks] = useState<GuestTrack[]>();
  const [playing, setPlaying] = useState<GuestTrack>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [audioError, setAudioError] = useState(false);

  async function search(event: FormEvent) {
    event.preventDefault();
    if (busy || query.trim().length < 2) return;
    setBusy(true);
    setError(undefined);
    try {
      setTracks(await request<GuestTrack[]>('GET', withQuery('/api/guest/search', { q: query.trim() })));
    } catch (err) {
      setTracks(undefined);
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto min-h-dvh max-w-2xl px-4 pb-8 pt-6">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold">HomeMusic</h1>
          {home.data?.track_count != null && <LibraryCount value={home.data.track_count} />}
        </div>
        <Link to="/login" className="flex min-h-11 items-center text-sm font-semibold text-accent">Войти или создать аккаунт</Link>
      </header>
      <form onSubmit={search} className="mb-4 flex gap-2">
        <input className="input min-w-0 flex-1" type="search" aria-label="Поиск треков"
          placeholder="Трек или исполнитель" value={query} onChange={(event) => setQuery(event.target.value)} maxLength={120} />
        <Button type="submit" busy={busy} disabled={query.trim().length < 2}>Найти</Button>
      </form>
      {playing && (
        <section className="mb-4 rounded-lg bg-raised p-3" aria-label="Плеер">
          <p className="mb-2 font-semibold">{playing.title} — {playing.artist}</p>
          <audio key={playing.ref} className="w-full" autoPlay controls aria-label={playing.title}
            src={withQuery('/api/guest/audio', { ref: playing.ref })} onError={() => setAudioError(true)} />
          {audioError && <Notice kind="error">Не удалось воспроизвести трек. Попробуйте позже.</Notice>}
        </section>
      )}
      {error && <Notice kind="error">{error}</Notice>}
      {tracks?.length === 0 && <p role="status" className="text-sm text-muted">Треки не найдены</p>}
      {tracks && tracks.length > 0 && (
        <ul className="divide-y divide-line">
          {tracks.map((track) => (
            <li key={track.ref} className="flex items-center gap-3 py-1">
              <button type="button" className="min-h-11 min-w-0 flex-1 text-left" onClick={() => {
                setAudioError(false);
                setPlaying(track);
              }}>
                <span className="block break-words font-semibold">{track.title}</span>
                <span className="block break-words text-sm text-muted">{track.artist}</span>
              </button>
              <a href={withQuery('/api/guest/audio', { ref: track.ref, download: true })} download
                aria-label={`Скачать «${track.title}»`} className="flex min-h-11 items-center text-sm font-semibold text-accent">Скачать</a>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
