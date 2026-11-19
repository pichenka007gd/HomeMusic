from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from app.config import YtdlpCfg
from app.net import make_client
from app.providers import ytdlp
from app.providers.base import Candidate


@pytest.fixture
def proxy_settings(monkeypatch):
    settings = SimpleNamespace(outbound_proxy="http://proxy:10809", proxy_hosts={"googlevideo.com", "soundcloud.com"})
    monkeypatch.setattr(ytdlp, "get_settings", lambda: settings)
    monkeypatch.setattr("app.net.get_settings", lambda: settings)
    return settings


async def test_media_subdomains_use_proxy(proxy_settings):
    async with make_client() as client:
        for host in ("googlevideo.com", "rr2---cache.googlevideo.com", "api-v2.soundcloud.com"):
            transport = client._transport_for_url(httpx.URL(f"https://{host}/"))
            assert transport is not client._transport
        assert client._transport_for_url(httpx.URL("http://youtube-pot:4416/")) is client._transport


async def test_proxy_fetch_keeps_native_extraction(monkeypatch, tmp_path, proxy_settings):
    class FakeYDL:
        def __init__(self, opts):
            assert opts["proxy"] == proxy_settings.outbound_proxy
            self.opts = opts

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def extract_info(self, url, *, download):
            assert download
            path = Path(self.opts["outtmpl"].replace("%(ext)s", "opus"))
            path.write_bytes(b"OggS")
            return {"requested_downloads": [{"filepath": str(path)}]}

    monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
    monkeypatch.setattr(ytdlp.yt_dlp, "YoutubeDL", FakeYDL)
    provider = ytdlp.YtdlpProvider(YtdlpCfg())
    candidate = Candidate(provider="ytdlp", provider_id="test", artist="Artist", title="Track",
                          page_url="https://www.youtube.com/watch?v=test")
    path = await provider.fetch(candidate, tmp_path)
    assert path.read_bytes() == b"OggS"
    await provider.aclose()


def test_soundcloud_ytdlp_uses_proxy(proxy_settings):
    from app.providers.reuploads import SoundcloudProvider

    assert SoundcloudProvider(YtdlpCfg())._base_opts()["proxy"] == proxy_settings.outbound_proxy
