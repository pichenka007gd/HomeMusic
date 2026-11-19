"""Anonymous search and audio; never grants a Hub or Navidrome session."""

from __future__ import annotations

import asyncio
import hmac
from ipaddress import ip_address
from time import time
from urllib.parse import quote

import httpx
from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from pydantic import BaseModel
from sqlalchemy import case, delete, or_
from sqlalchemy.dialects.sqlite import insert
from starlette.background import BackgroundTask

from app import listen
from app.api.medialib import track_count
from app.api.search import search_library
from app.catalog.deezer import DeezerError
from app.config import get_settings
from app.db import session_scope
from app.library.navidrome import SubsonicError, admin_client
from app.models import GuestUsage
from app.services import svc

router = APIRouter(prefix="/api/guest", tags=["guest"])
LIMITS = {"home": (15, 240), "search": (20, 120), "play": (90, 360), "download": (5, 40)}
REF = r"^(nd:[a-zA-Z0-9_-]{1,80}|dz:[0-9]{1,24})$"


class GuestTrack(BaseModel):
    ref: str
    title: str
    artist: str


async def _allow(request: Request, action: str) -> None:
    settings = get_settings()
    if not settings.public_guest_enabled:
        raise HTTPException(404, "Гостевой режим отключён")
    if not settings.hub_secret_key:
        raise HTTPException(503, "Гостевой режим не настроен")
    try:
        address = ip_address(request.client.host if request.client else "")
    except ValueError as exc:
        raise HTTPException(400, "Не удалось определить IP") from exc
    identity = hmac.digest(settings.hub_secret_key.encode(), b"guest:" + address.packed, "sha256").hex()
    minute = int(time()) // 60
    async with session_scope() as s:
        for key, limit in zip((identity, "global"), LIMITS[action], strict=True):
            stmt = insert(GuestUsage).values(profile=key, action=action, minute=minute, requests=1)
            row = await s.execute(stmt.on_conflict_do_update(
                index_elements=[GuestUsage.profile, GuestUsage.action],
                set_={"minute": minute, "requests": case((GuestUsage.minute == minute, GuestUsage.requests + 1), else_=1)},
                where=or_(GuestUsage.minute != minute, GuestUsage.requests < limit),
            ))
            if row.rowcount != 1:
                raise HTTPException(429, "Слишком много запросов. Попробуйте через минуту", headers={"Retry-After": "60"})
        await s.execute(delete(GuestUsage).where(GuestUsage.minute < minute - 24 * 60))


@router.get("/home")
async def home(request: Request):
    await _allow(request, "home")
    nd = admin_client()
    try:
        return {"track_count": await track_count(nd)}
    finally:
        await nd.aclose()


async def _library(q: str) -> list[GuestTrack]:
    async with session_scope() as s:
        return [GuestTrack(ref=f"nd:{t.nd_song_id}", artist=t.artist, title=t.title)
                for t in await search_library(s, q) if t.nd_song_id]


async def _catalog(q: str) -> list[GuestTrack]:
    if svc.deezer is None:
        return []
    tracks, _ = await svc.deezer.search_track_page(q, 0, 20)
    return [GuestTrack(ref=t.ref, artist=t.artist, title=t.title) for t in tracks if t.ref.startswith("dz:")]


@router.get("/search", response_model=list[GuestTrack])
async def search(request: Request, q: str = Query(min_length=2, max_length=120)):
    if len(q := q.strip()) < 2:
        raise HTTPException(422, "Введите не менее двух символов")
    await _allow(request, "search")
    results = await asyncio.gather(_library(q), _catalog(q), return_exceptions=True)
    if all(isinstance(result, Exception) for result in results):
        raise HTTPException(502, "Источники поиска недоступны")
    for result in results:
        if isinstance(result, Exception) and not isinstance(result, (SubsonicError, httpx.HTTPError, DeezerError)):
            raise result
    return list({t.ref: t for result in results if isinstance(result, list) for t in result}.values())


@router.get("/audio")
async def audio(request: Request, ref: str = Query(pattern=REF), download: bool = False):
    await _allow(request, "download" if download else "play")
    try:
        media = await listen.open_media(ref, request.headers.get("range"), full_only=download)
    except listen.ListenError as exc:
        raise HTTPException(exc.status, str(exc)) from exc
    headers = {"cache-control": "no-store"}
    if download:
        name = f"{media.choice.artist} - {media.choice.title}"
        suffix = media.path.suffix if media.path and media.path.suffix else {
            "audio/mpeg": ".mp3", "audio/mp4": ".m4a", "audio/ogg": ".ogg",
            "audio/flac": ".flac", "audio/webm": ".webm",
        }.get((media.media_type or "").split(";", 1)[0], "")
        headers["content-disposition"] = f"attachment; filename*=UTF-8''{quote(name + suffix, safe='')}"
    if media.path is not None:
        path = media.path.resolve()
        if media.choice.kind == "library" and not path.is_relative_to(get_settings().library_dir.resolve()):
            raise HTTPException(404, "Трек не найден")
        return FileResponse(path, media_type=media.media_type, headers=headers)
    headers = {**media.headers, **headers}
    if media.body is None:
        return Response(status_code=media.status, headers=headers)
    return StreamingResponse(media.body, status_code=media.status, headers=headers, media_type=media.media_type,
                             background=BackgroundTask(media.close) if media.close else None)
