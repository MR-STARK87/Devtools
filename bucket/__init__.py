"""Bucket: a private LAN drop box between this PC and paired phones."""

__all__ = ["create_app"]


def create_app(*args, **kwargs):
    from .server import create_app as _create_app

    return _create_app(*args, **kwargs)
