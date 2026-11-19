from secrets import token_urlsafe

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app import accounts, auth, mailer
from app.api.schemas import (
    AuthOptionsOut,
    EmailCodeIn,
    EmailCodeOut,
    EmailRegisterIn,
    GoogleIn,
    GoogleLinkIn,
    GoogleLoginOut,
    LoginIn,
    MeOut,
    RegisterIn,
)
from app.config import get_settings
from app.db import get_session
from app.models import User

router = APIRouter(prefix="/api/auth", tags=["auth"])
GOOGLE_NONCE_COOKIE = "__Host-hm_google_nonce"


def _google_web_origin() -> str:
    settings = get_settings()
    origin = settings.public_hub_url.rstrip("/")
    return origin if settings.google_client_id and origin.startswith("https://") else ""


def me_out(user: User) -> MeOut:
    settings = get_settings()
    return MeOut(id=user.id, username=user.nd_username, is_admin=user.is_admin,
                 public_music_url=settings.public_music_url, public_hub_url=settings.public_hub_url,
                 google_email=user.google_email, email=user.email)


@router.get("/options", response_model=AuthOptionsOut)
async def options():
    settings = get_settings()
    return AuthOptionsOut(invites=True, email_signup=mailer.configured(),
                          google_client_id=settings.google_client_id or None, music_url=settings.public_music_url,
                          google_web_enabled=bool(_google_web_origin()),
                          guest_enabled=settings.public_guest_enabled)


@router.post("/google/web/nonce")
async def google_nonce(request: Request, response: Response):
    _require_google_web(request)
    nonce = token_urlsafe(32)
    response.set_cookie(GOOGLE_NONCE_COOKIE, nonce, max_age=300, secure=True, httponly=True, samesite="strict", path="/")
    response.headers["Cache-Control"] = "no-store"
    return {"nonce": nonce}


def _require_google_web(request: Request) -> None:
    if not (origin := _google_web_origin()):
        raise HTTPException(404, "Вход через Google не настроен")
    if request.headers.get("origin") != origin:
        raise HTTPException(403, "Вход через Google доступен только с адреса сайта")


@router.post("/google/web", response_model=MeOut)
async def google_web(body: GoogleIn, request: Request, response: Response,
                     s: AsyncSession = Depends(get_session, scope="function")):
    _require_google_web(request)
    nonce = request.cookies.get(GOOGLE_NONCE_COOKIE)
    if not nonce:
        raise HTTPException(401, "Запрос входа через Google устарел, попробуйте ещё раз")
    user, token, _ = await accounts.google_login(s, body.id_token, body.code, body.username, nonce=nonce)
    auth.set_session_cookie(request, response, token)
    response.delete_cookie(GOOGLE_NONCE_COOKIE, path="/", secure=True, httponly=True, samesite="strict")
    response.headers["Cache-Control"] = "no-store"
    return me_out(user)


@router.post("/login", response_model=MeOut)
async def login(body: LoginIn, request: Request, response: Response, s: AsyncSession = Depends(get_session, scope="function")):
    username = await accounts.username_for_login(s, body.username)
    user, token = await auth.password_login(s, username, body.password)
    auth.set_session_cookie(request, response, token)
    return me_out(user)


@router.post("/register", response_model=MeOut)
async def register(body: RegisterIn, request: Request, response: Response,
                   s: AsyncSession = Depends(get_session, scope="function")):
    user, token = await accounts.register(s, body.code, body.username, body.password)
    auth.set_session_cookie(request, response, token)
    return me_out(user)


@router.post("/email/code", response_model=EmailCodeOut)
async def email_code(body: EmailCodeIn):
    return EmailCodeOut(resend_in=await accounts.send_email_code(body.email))


@router.post("/email/register", response_model=MeOut)
async def email_register(body: EmailRegisterIn, request: Request, response: Response,
                         s: AsyncSession = Depends(get_session, scope="function")):
    user, token = await accounts.register_by_email(s, body.email, body.code, body.username, body.password)
    auth.set_session_cookie(request, response, token)
    return me_out(user)


@router.post("/google", response_model=GoogleLoginOut)
async def google(body: GoogleIn, request: Request, response: Response,
                 s: AsyncSession = Depends(get_session, scope="function")):
    user, token, password = await accounts.google_login(s, body.id_token, body.code, body.username)
    auth.set_session_cookie(request, response, token)
    return GoogleLoginOut(**me_out(user).model_dump(), password=password)


@router.post("/google/link", response_model=MeOut)
async def google_link(body: GoogleLinkIn, user: User = Depends(auth.current_user),
                      s: AsyncSession = Depends(get_session, scope="function")):
    return me_out(await accounts.link_google(s, user, body.id_token))


@router.delete("/google/link", response_model=MeOut)
async def google_unlink(user: User = Depends(auth.current_user), s: AsyncSession = Depends(get_session, scope="function")):
    user.google_sub = user.google_email = None
    return me_out(user)


@router.post("/logout")
async def logout(request: Request, response: Response, s: AsyncSession = Depends(get_session, scope="function")):
    await auth.logout(s, request, response)
    return {"ok": True}


@router.get("/me", response_model=MeOut)
async def me(user: User = Depends(auth.current_user)):
    return me_out(user)
