import httpx
import pytest
from fastapi import FastAPI, Request
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

from app.api.guest import _guest_address


async def test_cloudflare_visitor_ip_requires_both_trusted_hops():
    app = FastAPI()

    @app.get("/")
    async def whoami(request: Request):
        return str(_guest_address(request, "203.0.113.0/24"))

    wrapped = ProxyHeadersMiddleware(app, trusted_hosts="192.0.2.4")
    headers = {"X-Forwarded-For": "203.0.113.5", "X-Forwarded-Proto": "https", "CF-Connecting-IP": "198.51.100.5"}
    for peer, expected in (("192.0.2.4", "198.51.100.5"), ("192.0.2.9", "192.0.2.9")):
        transport = httpx.ASGITransport(app=wrapped, client=(peer, 12345))
        async with httpx.AsyncClient(transport=transport, base_url="http://hub.test") as client:
            response = await client.get("/", headers=headers)
            assert response.json() == expected
            if peer == "192.0.2.4":
                response = await client.get("/", headers={**headers, "X-Forwarded-For": "192.0.2.9"})
                assert response.json() == "192.0.2.9"
                response = await client.get("/", headers={**headers, "CF-Connecting-IP": "not-an-ip"})
                assert response.status_code == 400


@pytest.mark.parametrize("cidrs", ("0.0.0.0/0", "::/0", "invalid"))
def test_cloudflare_config_rejects_wildcards_and_invalid_ranges(cidrs):
    from pydantic import ValidationError

    from app.config import Settings

    with pytest.raises(ValidationError):
        Settings(guest_cloudflare_edge_cidrs=cidrs)
