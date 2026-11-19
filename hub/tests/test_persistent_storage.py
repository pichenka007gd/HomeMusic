from types import SimpleNamespace

import pytest

from app import listen
from app.library import storage
from app.providers.base import Candidate


@pytest.fixture
def storage_settings(tmp_path, monkeypatch):
    settings = SimpleNamespace(staging_dir=tmp_path, cfg=SimpleNamespace(
        library=SimpleNamespace(persistent_audio_cache=True, min_free_gb=5)))
    monkeypatch.setattr(listen, "get_settings", lambda: settings)
    monkeypatch.setattr(storage, "get_settings", lambda: settings)
    return settings


def test_storage_reserves_free_space(tmp_path, monkeypatch, storage_settings):
    monkeypatch.setattr(storage.shutil, "disk_usage", lambda path: SimpleNamespace(free=6 * 2**30))
    storage.require_space(tmp_path, 2**30)
    with pytest.raises(storage.StorageFull, match="Освободите место"):
        storage.require_space(tmp_path, 2 * 2**30)


def test_persistent_cache_is_not_pruned(tmp_path, storage_settings):
    root = tmp_path / "cache"
    folder = root / "old"
    folder.mkdir(parents=True)
    audio = folder / "stream"
    audio.write_bytes(b"audio")
    listen.prune_local(root, set(), now=10**12)
    assert audio.read_bytes() == b"audio"
    storage_settings.cfg.library.persistent_audio_cache = False
    listen.prune_local(root, set(), now=10**12)
    assert not audio.exists()


async def test_completed_audio_survives_cache_expiry(tmp_path, monkeypatch, storage_settings):
    ref = "file:ytdlp:test"
    folder = tmp_path / listen.LOCAL_DIR / listen._local_name(ref)
    folder.mkdir(parents=True)
    audio = folder / "stream"
    audio.write_bytes(b"OggS audio")
    candidate = Candidate(provider="ytdlp", provider_id="test", artist="Artist", title="Song",
                          page_url="https://www.youtube.com/watch?v=test")
    choice = listen.Choice(ref, "full", "Artist", "Song", candidate=candidate, heard=True, verified=True)
    listen._save_audio_copy(choice, audio, "audio/ogg")

    async def expired(*args, **kwargs):
        raise AssertionError("completed audio must not depend on database cache or upstream")

    monkeypatch.setattr(listen, "cache_get", expired)
    monkeypatch.setattr(listen, "open_stream", expired)
    recovered = await listen._remembered(ref, library=False)
    assert recovered.local == audio
    assert recovered.local_type == "audio/ogg"
    assert await listen._opens(recovered)

    async def choose(*args, **kwargs):
        return recovered

    monkeypatch.setattr(listen, "choose", choose)
    media = await listen.open_media(ref, None)
    assert media.path == audio and media.media_type == "audio/ogg"
    audio.write_bytes(b"cut")
    assert listen._cached_audio(ref) is None
