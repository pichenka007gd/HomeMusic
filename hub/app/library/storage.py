import shutil
from pathlib import Path

from app.config import get_settings
from app.providers.base import ProviderError


class StorageFull(ProviderError):
    pass


def require_space(path: Path, incoming: int = 0) -> None:
    reserve = int(get_settings().cfg.library.min_free_gb * 2**30)
    if not reserve:
        return
    path = Path(path)
    while not path.exists():
        path = path.parent
    free = shutil.disk_usage(path).free
    if free - incoming < reserve:
        raise StorageFull(
            f"Загрузка остановлена: свободно {free / 2**30:.2f} ГиБ, "
            f"нужно оставить минимум {reserve / 2**30:.1f} ГиБ. Освободите место на диске."
        )
