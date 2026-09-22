"""Rambler — speech-to-thought cleanup engine via Ollama Cloud.

No local model, no Ollama daemon — direct HTTPS to https://ollama.com.
The PC holds the API key (env / .env); the phone only sends a boolean.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path

# Keep in sync with .env.example
DEFAULT_MODEL = "gpt-oss:20b"
DEFAULT_HOST = "https://ollama.com"
ENV_FILE = Path(__file__).resolve().parent.parent / ".env"

SYSTEM_PROMPT = (
    "You are Rambler, a speech-to-thought cleanup engine. You take raw, unstructured voice transcripts — full of filler words, false starts, repetition, and tangents — and turn them into clean, tightened prose that captures exactly what the person meant to say.\n"
    "\n"
    "Rules:\n"
    "1. Remove all filler words, verbal tics, false starts, and stutters (\"um,\" \"like,\" \"you know,\" \"I mean,\" repeated words, self-corrections).\n"
    "2. Collapse repetition and rambling — if the same point is made three different ways, say it once, well.\n"
    "3. Reorganize for logical flow if the speaker jumped around, but never invent structure that changes their meaning.\n"
    "4. Tighten and rephrase for clarity — you may rewrite sentences, merge fragments, cut redundancy, and improve word choice. Prioritize how a sharp, articulate person would say the same thought, not a literal transcript edit.\n"
    "5. Preserve the speaker's actual ideas, opinions, and intent exactly. Never add information, conclusions, or claims they didn't make. Never soften or exaggerate their stance.\n"
    "6. Preserve their voice — if they're casual, keep it casual; if technical, keep the terminology. Don't flatten it into generic corporate tone.\n"
    "7. Output clean prose paragraphs only. No bullet points, no headers, no meta-commentary like \"Here's the cleaned version.\" Just the result.\n"
    "8. If the transcript contains genuinely separate thoughts or topics, use paragraph breaks to separate them — don't force everything into one block.\n"
    "\n"
    "Input: a raw, messy voice transcript.\n"
    "Output: the same thought, said clearly.\n"
)


def _load_env_file(overwrite_placeholder: bool = True) -> None:
    """Load .env (key=value) into os.environ. No deps.

    If overwrite_placeholder is True, a real key in the file will overwrite
    a placeholder already in the environment — this lets the user edit .env
    without restarting the process and have it take effect on the next request.
    """
    try:
        if not ENV_FILE.exists():
            return
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.strip().strip('"').strip("'")
            if not k:
                continue
            existing = os.environ.get(k, "")
            # If file has a real key, let it overwrite a placeholder value
            if overwrite_placeholder and k == "OLLAMA_API_KEY":
                placeholders = {"", "your_api_key_here", "placeholder", "changeme", "xxx"}
                if existing in placeholders and v not in placeholders and len(v) > 10:
                    os.environ[k] = v
                    continue
                if existing not in placeholders:
                    # Already has a real key in env — don't clobber
                    continue
            if k and k not in os.environ:
                os.environ[k] = v
    except Exception:
        # .env is best-effort; never crash the server over it
        pass


_load_env_file()


def _strip_cloud_suffix(model: str) -> str:
    # Direct API expects "gpt-oss:20b", not "gpt-oss:20b-cloud"
    if model.endswith("-cloud"):
        return model[: -len("-cloud")]
    if model.endswith(":cloud"):
        return model[: -len(":cloud")]
    return model


def get_config() -> dict:
    # Re-read .env on every call so editing the file without restart works
    _load_env_file(overwrite_placeholder=True)
    host = os.environ.get("OLLAMA_HOST", DEFAULT_HOST).rstrip("/")
    model = _strip_cloud_suffix(os.environ.get("OLLAMA_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL)
    key = os.environ.get("OLLAMA_API_KEY", "").strip()
    # Treat placeholders as not configured
    placeholder_keys = {"", "your_api_key_here", "placeholder", "changeme", "xxx"}
    configured = key not in placeholder_keys and len(key) > 10
    return {"host": host, "model": model, "configured": configured, "has_key": configured}


def is_configured() -> bool:
    return get_config()["configured"]


def clean_text(raw: str, timeout: float = 15.0) -> str:
    """Call Ollama Cloud and return cleaned text. Raises on failure."""
    if not raw or not raw.strip():
        return raw
    cfg = get_config()
    if not cfg["configured"]:
        raise RuntimeError("Ollama API key not configured (set OLLAMA_API_KEY in .env)")

    host = cfg["host"]
    model = cfg["model"]
    key = os.environ.get("OLLAMA_API_KEY", "").strip()

    # Truncate extremely long inputs at the API boundary (server also enforces 50k)
    # Keep within model context comfortably
    if len(raw) > 12000:
        raw = raw[:12000]

    url = f"{host}/api/chat"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": raw},
        ],
        "stream": False,
        "options": {"temperature": 0.1},
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            obj = json.loads(body)
            # Ollama returns {"message": {"content": "..."}} or streaming chunks
            content = None
            if isinstance(obj, dict):
                msg = obj.get("message")
                if isinstance(msg, dict):
                    content = msg.get("content")
                if content is None:
                    # fallback: some deployments wrap differently
                    content = obj.get("response") or obj.get("content")
            if not isinstance(content, str):
                raise RuntimeError(f"unexpected Ollama response: {body[:500]}")
            cleaned = content.strip()
            # Strip surrounding quotes if model added them despite instructions
            if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in ('"', "'"):
                cleaned = cleaned[1:-1].strip()
            return cleaned if cleaned else raw
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="ignore")[:800] if hasattr(e, "read") else str(e)
        raise RuntimeError(f"Ollama Cloud HTTP {e.code}: {err_body}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"Ollama Cloud network error: {e.reason}") from e
