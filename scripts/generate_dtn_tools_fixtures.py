#!/usr/bin/env python3
"""
generate_dtn_tools_fixtures.py
Generates binary BPv7 test fixtures using NASA's dtn-tools package for dtn-android-messenger unit testing.
"""

import sys
import os
from pathlib import Path

# Ensure ../dtn-tools is in python path
script_dir = Path(__file__).resolve().parent
repo_root = script_dir.parent
dtn_tools_path = repo_root.parent / "dtn-tools"

if dtn_tools_path.exists():
    sys.path.insert(0, str(dtn_tools_path))

try:
    from dtntools.dtngen.bundle import Bundle
    from dtntools.dtngen.blocks import (
        PrimaryBlock,
        PayloadBlock,
        HopCountBlock,
        PrevNodeBlock,
        BundleAgeBlock,
        UnknownBlock,
    )
    from dtntools.dtngen.types import (
        EID,
        BundlePCFlags,
        CRCType,
        CRCFlag,
        BlockType,
        CreationTimestamp,
        HopCountData,
    )
    from dtntools.dtncla.errors.inject import inject_errors
except ImportError as e:
    print(f"Error importing dtntools: {e}", file=sys.stderr)
    print("Ensure cbor2 and crccheck are installed and ../dtn-tools directory exists.", file=sys.stderr)
    sys.exit(1)


def generate_fixtures(output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"Generating BPv7 test fixtures in {output_dir}...")

    # Helper for EIDs (uri 1 = dtn)
    eid_dest = EID({"uri": 1, "ssp": "//node-b/chat"})
    eid_src = EID({"uri": 1, "ssp": "//node-a/chat"})
    eid_prev = EID({"uri": 1, "ssp": "//node-prev/chat"})

    # Common Primary Block
    pri_base = lambda crc_type=CRCType.NONE, crc=None: PrimaryBlock(
        version=7,
        control_flags=BundlePCFlags.MUST_NOT_FRAGMENT,
        crc_type=crc_type,
        dest_eid=eid_dest,
        src_eid=eid_src,
        rpt_eid=eid_src,
        creation_timestamp=CreationTimestamp({"time": 1000000, "sequence": 1}),
        lifetime=3600000,
        crc=crc,
    )

    # 1. Nominal Basic Bundle
    pay_basic = PayloadBlock(
        blk_type=BlockType.BUNDLE_PAYLOAD,
        blk_num=1,
        control_flags=0,
        crc_type=CRCType.NONE,
        payload=b"Hello DTN Tools Nominal Payload",
        crc=None,
    )
    b_nominal = Bundle(pri_block=pri_base(), canon_blocks=[pay_basic])
    with open(output_dir / "nominal_basic.bundle", "wb") as f:
        f.write(b_nominal.to_bytes())

    # 2. Nominal Hop Count Bundle
    hop_block = HopCountBlock(
        blk_type=BlockType.HOP_COUNT,
        blk_num=2,
        control_flags=0,
        crc_type=CRCType.NONE,
        hop_data=HopCountData({"hop_limit": 15, "hop_count": 3}),
        crc=None,
    )
    b_hop = Bundle(pri_block=pri_base(), canon_blocks=[hop_block, pay_basic])
    with open(output_dir / "nominal_hop_count.bundle", "wb") as f:
        f.write(b_hop.to_bytes())

    # 3. Nominal Previous Node Bundle
    prev_node_block = PrevNodeBlock(
        blk_type=BlockType.PREVIOUS_NODE,
        blk_num=3,
        control_flags=0,
        crc_type=CRCType.NONE,
        prev_eid=eid_prev,
        crc=None,
    )
    b_prev = Bundle(pri_block=pri_base(), canon_blocks=[prev_node_block, pay_basic])
    with open(output_dir / "nominal_prev_node.bundle", "wb") as f:
        f.write(b_prev.to_bytes())

    # 4. Nominal Bundle Age Bundle
    age_block = BundleAgeBlock(
        blk_type=BlockType.BUNDLE_AGE,
        blk_num=4,
        control_flags=0,
        crc_type=CRCType.NONE,
        bundle_age=10800,
        crc=None,
    )
    b_age = Bundle(pri_block=pri_base(), canon_blocks=[age_block, pay_basic])
    with open(output_dir / "nominal_bundle_age.bundle", "wb") as f:
        f.write(b_age.to_bytes())

    # 5. Invalid CRC Bundle
    pri_crc = pri_base(crc_type=CRCType.CRC16_X25, crc=CRCFlag.CALCULATE)
    pay_crc = PayloadBlock(
        blk_type=BlockType.BUNDLE_PAYLOAD,
        blk_num=1,
        control_flags=0,
        crc_type=CRCType.CRC16_X25,
        payload=b"Corrupt CRC Payload",
        crc=CRCFlag.CALCULATE,
    )
    b_crc = Bundle(pri_block=pri_crc, canon_blocks=[pay_crc])
    crc_bytes = bytearray(b_crc.to_bytes())
    # Corrupt last 2 bytes (CRC)
    crc_bytes[-1] ^= 0xFF
    crc_bytes[-2] ^= 0xFF
    with open(output_dir / "invalid_crc.bundle", "wb") as f:
        f.write(bytes(crc_bytes))

    # 6. Invalid BP Version (Version 8)
    b_bytes = bytearray(b_nominal.to_bytes())
    for i in range(len(b_bytes) - 1):
        if b_bytes[i] == 0x07:
            b_bytes[i] = 0x08
            break
    with open(output_dir / "invalid_version.bundle", "wb") as f:
        f.write(bytes(b_bytes))

    # 7. Invalid BPSec BCB (Block Type 12 - forbidden under cleartext amateur rules)
    bcb_block = UnknownBlock(
        elements=[
            12,  # Block Type 12 = BPSec BCB
            5,   # Block Num
            0,   # Control flags
            0,   # CRC Type None
            b"\x01\x02\x03\x04", # Encrypted block data payload
        ]
    )
    b_bcb = Bundle(pri_block=pri_base(), canon_blocks=[bcb_block, pay_basic])
    with open(output_dir / "invalid_bcb_encrypted.bundle", "wb") as f:
        f.write(b_bcb.to_bytes())

    # 8. Bit-Flipped Corrupted Bundle
    raw_nominal = b_nominal.to_bytes()
    corrupted_bytes = inject_errors(raw_nominal, error_rate=16)
    with open(output_dir / "corrupted_bitflips.bundle", "wb") as f:
        f.write(corrupted_bytes)

    print("Successfully generated all fixtures:")
    for p in sorted(output_dir.glob("*.bundle")):
        print(f"  - {p.name} ({p.stat().st_size} bytes)")


if __name__ == "__main__":
    out_dir = repo_root / "app" / "src" / "test" / "resources" / "fixtures" / "dtn_tools"
    generate_fixtures(out_dir)
