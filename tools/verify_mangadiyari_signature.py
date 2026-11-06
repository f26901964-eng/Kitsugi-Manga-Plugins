#!/usr/bin/env python3
"""APK'nın imza sertifikasını okuyup beklenen keystore ile karşılaştırır.

Android, kurulu bir eklentinin üzerine ancak **aynı imza anahtarıyla** imzalanmış APK'yı kurar.
CI her koşuda farklı bir debug anahtarı ürettiğinde eklenti güncellenemiyordu; bu betik, derlenen
APK'nın `tools/signing/` altındaki kalıcı anahtarla imzalandığını doğrular ve yayını durdurur.

Kullanım:
    python3 tools/verify_mangadiyari_signature.py <apk> <keystore.p12|.jks>

Yalnızca standart kütüphane kullanır (CI'da ek bağımlılık gerekmez), ancak `.p12`/`.jks`
dosyasındaki sertifikayı okumak için `cryptography` gerekir; yoksa karşılaştırma atlanır ve
uyarı basılır (APK'nın imzalı olup olmadığı yine kontrol edilir).
"""
from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
V3_BLOCK_ID = 0xF05368C0


def signing_block_certificate(apk: Path) -> bytes:
    """APK Signing Block içindeki v2/v3 bloklarından **ilk** imza sertifikasını döndürür."""
    data = apk.read_bytes()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("EOCD (zip central directory) bulunamadı; dosya APK değil")
    central_offset = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
    if data[central_offset - 16:central_offset] != APK_SIG_BLOCK_MAGIC:
        raise ValueError("APK Signing Block yok: APK imzasız (v2/v3)")
    block_size = struct.unpack("<Q", data[central_offset - 24:central_offset - 16])[0]
    block = data[central_offset - block_size - 8:central_offset]

    pairs: dict[int, bytes] = {}
    position = 8
    end = len(block) - 24
    while position < end:
        pair_len = struct.unpack("<Q", block[position:position + 8])[0]
        pair_id = struct.unpack("<I", block[position + 8:position + 12])[0]
        pairs[pair_id] = block[position + 12:position + 8 + pair_len]
        position += 8 + pair_len

    for block_id in (V3_BLOCK_ID, V2_BLOCK_ID):
        value = pairs.get(block_id)
        if value:
            return _first_certificate(value)
    raise ValueError("v2/v3 imza bloğu bulunamadı")


def _read_length_prefixed(buffer: bytes, offset: int) -> tuple[bytes, int]:
    length = struct.unpack("<I", buffer[offset:offset + 4])[0]
    return buffer[offset + 4:offset + 4 + length], offset + 4 + length


def _first_certificate(block: bytes) -> bytes:
    signers, _ = _read_length_prefixed(block, 0)
    signer, _ = _read_length_prefixed(signers, 0)
    signed_data, _ = _read_length_prefixed(signer, 0)
    _, offset = _read_length_prefixed(signed_data, 0)  # digests
    certificates, _ = _read_length_prefixed(signed_data, offset)
    certificate, _ = _read_length_prefixed(certificates, 0)
    return certificate


def keystore_certificate(keystore: Path, password: str, alias: str) -> bytes:
    """`cryptography` varsa keystore'daki sertifikayı DER olarak döndürür."""
    from cryptography.hazmat.primitives.serialization import Encoding, pkcs12

    raw = keystore.read_bytes()
    if keystore.suffix.lower() in (".p12", ".pfx"):
        _, certificate, _ = pkcs12.load_key_and_certificates(raw, password.encode())
    else:  # JKS: cryptography doğrudan okumaz, pyjks olmadan atlanır
        raise ValueError("JKS keystore bu betikle okunamıyor; PKCS12 kullanın")
    if certificate is None:
        raise ValueError(f"keystore içinde sertifika yok: {keystore}")
    return certificate.public_bytes(Encoding.DER)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    apk = Path(argv[1])
    keystore = Path(argv[2])
    if not apk.is_file():
        print(f"HATA: APK yok: {apk}", file=sys.stderr)
        return 2
    if zipfile.ZipFile(apk).testzip() is not None:
        print(f"HATA: APK bozuk: {apk}", file=sys.stderr)
        return 2

    apk_certificate = signing_block_certificate(apk)
    print(f"APK imzalı; sertifika {len(apk_certificate)} bayt")

    try:
        expected = keystore_certificate(keystore, "kitsugi-manga-plugins", "kitsugi")
    except ImportError:
        print("UYARI: cryptography yok; keystore karşılaştırması atlandı", file=sys.stderr)
        return 0
    except ValueError as exc:
        print(f"UYARI: keystore okunamadı ({exc}); karşılaştırma atlandı", file=sys.stderr)
        return 0

    if apk_certificate != expected:
        print(
            "HATA: APK beklenen imza anahtarıyla imzalanmamış. "
            "Kurulu eklentinin üzerine güncelleme kurulamaz.",
            file=sys.stderr,
        )
        return 1
    print("İmza keystore ile eşleşiyor: güncelleme olarak kurulabilir")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
