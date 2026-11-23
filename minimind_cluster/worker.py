"""
Redmi 13C — Compute Worker for Distributed MiniMind Training.
Connects to Redmi 9C (Director) over local Wi-Fi, receives weights + micro-batches,
computes real forward + backward gradients, and returns them for All-Reduce.
"""

import argparse
import asyncio
import json
import os
import platform
from typing import Optional

from minimind_cluster.model import MiniMindModel
from minimind_cluster.protocol import (
    build_ws_frame,
    connect_ws_client,
    read_ws_frame,
    send_ws_text,
)


async def run_worker(host: str, port: int, name: str, worker_id: str) -> None:
    print(f"[{name}] Conectando al Director Redmi 9C en ws://{host}:{port}/ws ...")
    reader, writer = await connect_ws_client(host, port, "/ws")

    reg_msg = json.dumps(
        {
            "type": "register_worker",
            "worker_id": worker_id,
            "name": name,
            "info": {
                "arch": platform.machine(),
                "cpus": os.cpu_count() or 4,
            },
        }
    )
    await send_ws_text(writer, reg_msg, mask=True)

    model: Optional[MiniMindModel] = None
    loop = asyncio.get_running_loop()

    try:
        while True:
            frame = await read_ws_frame(reader)
            if frame is None:
                print(f"[{name}] Conexión cerrada por el Director.")
                break
            opcode, payload = frame
            if opcode == 0x8:
                break
            if opcode == 0x9:
                writer.write(build_ws_frame(payload, opcode=0xA, mask=True))
                await writer.drain()
                continue
            if opcode != 0x1:
                continue

            data = json.loads(payload.decode("utf-8"))
            msg_type = data.get("type")

            if msg_type == "registered":
                cfg = data["config"]
                model = MiniMindModel(
                    vocab_size=cfg["vocab_size"],
                    d_model=cfg["d_model"],
                    d_ff=cfg["d_ff"],
                    max_seq_len=cfg["max_seq_len"],
                )
                print(f"[{name}] Registrado exitosamente en el clúster MiniMind (vocab={cfg['vocab_size']}).")

            elif msg_type == "compute_grad" and model is not None:
                step = data["step"]
                model.set_weights(data["weights"])
                input_ids = data["input_ids"]
                target_ids = data["target_ids"]

                loss, grads = await loop.run_in_executor(
                    None, model.forward_backward, input_ids, target_ids
                )

                res_msg = json.dumps(
                    {
                        "type": "grad_result",
                        "step": step,
                        "loss": loss,
                        "grads": grads,
                    }
                )
                await send_ws_text(writer, res_msg, mask=True)
                print(f"[{name}] Paso {step} completado | Loss local: {loss:.4f}")
    finally:
        if not writer.is_closing():
            writer.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="MiniMind Worker (Redmi 13C)")
    parser.add_argument("--host", required=True, help="IP del Redmi 9C (Director)")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--name", default="Redmi 13C (Worker)")
    parser.add_argument("--id", default="redmi-13c-worker")
    args = parser.parse_args()

    asyncio.run(run_worker(args.host, args.port, args.name, args.id))


if __name__ == "__main__":
    main()
