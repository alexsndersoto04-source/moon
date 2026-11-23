"""
Redmi 9C — Principal Director (Master/Coordinator) for Distributed MiniMind Training.
- Serves the mobile-friendly Web Dashboard (HTTP)
- Coordinates connected devices (Redmi 13C + Redmi 9C local compute) via WebSocket
- Splits training batches, aggregates gradients (All-Reduce), updates global weights
"""

import argparse
import asyncio
import json
import os
import socket
import time
from typing import Any, Dict, List, Optional, Tuple

from minimind_cluster.model import CharTokenizer, MiniMindModel, average_gradients
from minimind_cluster.protocol import (
    build_ws_frame,
    compute_ws_accept,
    read_ws_frame,
    send_ws_text,
)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB_DIR = os.path.join(BASE_DIR, "minimind_cluster", "web")
DEFAULT_DATA = os.path.join(BASE_DIR, "data", "sample_corpus.txt")
CHECKPOINT_PATH = os.path.join(BASE_DIR, "minimind_checkpoint.json")


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class WorkerConn:
    def __init__(self, worker_id: str, name: str, writer: asyncio.StreamWriter, info: dict):
        self.worker_id = worker_id
        self.name = name
        self.writer = writer
        self.info = info
        self.pending_future: Optional[asyncio.Future] = None
        self.steps_completed = 0


class DirectorServer:
    def __init__(self, host: str = "0.0.0.0", port: int = 8080, data_path: str = DEFAULT_DATA):
        self.host = host
        self.port = port
        self.data_path = data_path

        with open(self.data_path, "r", encoding="utf-8") as f:
            text = f.read()

        self.tokenizer = CharTokenizer(text)
        self.tokens = self.tokenizer.encode(text)
        self.seq_len = 24
        self.model = MiniMindModel(
            vocab_size=self.tokenizer.vocab_size,
            d_model=32,
            d_ff=64,
            max_seq_len=self.seq_len,
        )

        self.workers: Dict[str, WorkerConn] = {}
        self.dashboards: List[asyncio.StreamWriter] = []
        self.is_training = False
        self.training_task: Optional[asyncio.Task] = None
        self.loss_history: List[Dict[str, Any]] = []
        self.data_cursor = 0
        self.director_info = {
            "id": "redmi-9c-director",
            "name": "Redmi 9C (Director)",
            "role": "director",
            "status": "ready",
        }

    def _next_batch(self) -> Tuple[List[int], List[int]]:
        if self.data_cursor + self.seq_len + 1 >= len(self.tokens):
            self.data_cursor = 0
        chunk = self.tokens[self.data_cursor : self.data_cursor + self.seq_len + 1]
        self.data_cursor += self.seq_len // 2
        return chunk[:-1], chunk[1:]

    def get_cluster_state(self) -> dict:
        devices = [
            {
                "id": "redmi-9c-director",
                "name": "Redmi 9C (Director)",
                "role": "Director / Master",
                "status": "training" if self.is_training else "ready",
                "steps": self.model.step_count,
            }
        ]
        for w in self.workers.values():
            devices.append(
                {
                    "id": w.worker_id,
                    "name": w.name,
                    "role": "Worker (Compute)",
                    "status": "training" if self.is_training else "connected",
                    "steps": w.steps_completed,
                    "info": w.info,
                }
            )
        return {
            "type": "cluster_state",
            "is_training": self.is_training,
            "step": self.model.step_count,
            "vocab_size": self.tokenizer.vocab_size,
            "devices": devices,
            "loss_history": self.loss_history[-50:],
            "local_ip": get_local_ip(),
            "port": self.port,
        }

    async def broadcast_dashboards(self, payload: dict) -> None:
        msg = json.dumps(payload)
        dead = []
        for w in self.dashboards:
            try:
                await send_ws_text(w, msg, mask=False)
            except Exception:
                dead.append(w)
        for d in dead:
            if d in self.dashboards:
                self.dashboards.remove(d)

    async def training_loop(self, max_steps: int = 0) -> None:
        self.is_training = True
        await self.broadcast_dashboards(self.get_cluster_state())

        try:
            step_done = 0
            while self.is_training:
                if max_steps > 0 and step_done >= max_steps:
                    break

                t0 = time.time()
                weights = self.model.get_weights()

                # 1. Assign micro-batches to all connected workers (e.g. Redmi 13C)
                worker_futures: List[Tuple[WorkerConn, asyncio.Future]] = []
                loop = asyncio.get_running_loop()

                for w in list(self.workers.values()):
                    inp, tgt = self._next_batch()
                    fut = loop.create_future()
                    w.pending_future = fut
                    task_msg = json.dumps(
                        {
                            "type": "compute_grad",
                            "step": self.model.step_count + 1,
                            "weights": weights,
                            "input_ids": inp,
                            "target_ids": tgt,
                        }
                    )
                    try:
                        await send_ws_text(w.writer, task_msg, mask=False)
                        worker_futures.append((w, fut))
                    except Exception:
                        w.pending_future = None

                # 2. Redmi 9C (Director) also computes its own micro-batch in parallel
                dir_inp, dir_tgt = self._next_batch()
                dir_loss, dir_grads = await loop.run_in_executor(
                    None, self.model.forward_backward, dir_inp, dir_tgt
                )

                all_grads = [dir_grads]
                losses = [dir_loss]

                # 3. Gather gradients from Redmi 13C (and any other workers)
                for w, fut in worker_futures:
                    try:
                        res = await asyncio.wait_for(fut, timeout=30.0)
                        all_grads.append(res["grads"])
                        losses.append(res["loss"])
                        w.steps_completed += 1
                    except Exception:
                        pass
                    finally:
                        w.pending_future = None

                # 4. All-Reduce (average gradients) & update global weights
                avg_grads = average_gradients(all_grads)
                self.model.apply_adamw(avg_grads, lr=0.015)

                avg_loss = sum(losses) / len(losses)
                dt = time.time() - t0
                tokens_per_sec = (self.seq_len * len(all_grads)) / max(dt, 1e-4)

                entry = {
                    "step": self.model.step_count,
                    "loss": round(avg_loss, 4),
                    "tokens_per_sec": round(tokens_per_sec, 1),
                    "devices": len(all_grads),
                }
                self.loss_history.append(entry)
                step_done += 1

                if self.model.step_count % 10 == 0:
                    self.model.save_checkpoint(CHECKPOINT_PATH, self.tokenizer)

                await self.broadcast_dashboards(
                    {
                        "type": "train_step",
                        **entry,
                        "cluster": self.get_cluster_state(),
                    }
                )
                await asyncio.sleep(0.05)
        finally:
            self.is_training = False
            self.model.save_checkpoint(CHECKPOINT_PATH, self.tokenizer)
            await self.broadcast_dashboards(self.get_cluster_state())

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            req_line = await reader.readline()
            if not req_line:
                writer.close()
                return
            parts = req_line.decode("utf-8", errors="ignore").strip().split()
            if len(parts) < 2:
                writer.close()
                return
            method, path = parts[0], parts[1]

            headers: Dict[str, str] = {}
            while True:
                line = await reader.readline()
                if not line or line == b"\r\n":
                    break
                if b":" in line:
                    k, v = line.decode("utf-8", errors="ignore").split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            if headers.get("upgrade", "").lower() == "websocket":
                ws_key = headers.get("sec-websocket-key", "")
                accept = compute_ws_accept(ws_key)
                resp = (
                    "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
                )
                writer.write(resp.encode("utf-8"))
                await writer.drain()
                await self.handle_ws(reader, writer)
                return

            # Serve HTTP endpoints
            if path == "/" or path == "/index.html":
                html_path = os.path.join(WEB_DIR, "index.html")
                with open(html_path, "rb") as f:
                    body = f.read()
                header = (
                    "HTTP/1.1 200 OK\r\n"
                    "Content-Type: text/html; charset=utf-8\r\n"
                    f"Content-Length: {len(body)}\r\n"
                    "Connection: close\r\n\r\n"
                )
                writer.write(header.encode("utf-8") + body)
                await writer.drain()
            elif path == "/api/status":
                body = json.dumps(self.get_cluster_state()).encode("utf-8")
                header = (
                    "HTTP/1.1 200 OK\r\n"
                    "Content-Type: application/json\r\n"
                    f"Content-Length: {len(body)}\r\n"
                    "Connection: close\r\n\r\n"
                )
                writer.write(header.encode("utf-8") + body)
                await writer.drain()
            else:
                body = b"Not Found"
                header = (
                    "HTTP/1.1 404 Not Found\r\n"
                    f"Content-Length: {len(body)}\r\n"
                    "Connection: close\r\n\r\n"
                )
                writer.write(header.encode("utf-8") + body)
                await writer.drain()
        except Exception:
            pass
        finally:
            if not writer.is_closing():
                writer.close()

    async def handle_ws(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        worker_id: Optional[str] = None
        is_dashboard = False

        try:
            while True:
                frame = await read_ws_frame(reader)
                if frame is None:
                    break
                opcode, payload = frame
                if opcode == 0x8:  # Close
                    break
                if opcode == 0x9:  # Ping
                    writer.write(build_ws_frame(payload, opcode=0xA))
                    await writer.drain()
                    continue
                if opcode != 0x1:
                    continue

                data = json.loads(payload.decode("utf-8"))
                msg_type = data.get("type")

                if msg_type == "register_worker":
                    worker_id = data.get("worker_id", f"worker-{len(self.workers)+1}")
                    name = data.get("name", "Redmi 13C (Worker)")
                    info = data.get("info", {})
                    self.workers[worker_id] = WorkerConn(worker_id, name, writer, info)
                    await send_ws_text(
                        writer,
                        json.dumps(
                            {
                                "type": "registered",
                                "worker_id": worker_id,
                                "config": {
                                    "vocab_size": self.tokenizer.vocab_size,
                                    "d_model": self.model.d_model,
                                    "d_ff": self.model.d_ff,
                                    "max_seq_len": self.model.max_seq_len,
                                },
                            }
                        ),
                    )
                    await self.broadcast_dashboards(self.get_cluster_state())

                elif msg_type == "grad_result" and worker_id in self.workers:
                    w = self.workers[worker_id]
                    if w.pending_future and not w.pending_future.done():
                        w.pending_future.set_result(
                            {"loss": data["loss"], "grads": data["grads"]}
                        )

                elif msg_type == "register_dashboard":
                    is_dashboard = True
                    self.dashboards.append(writer)
                    await send_ws_text(writer, json.dumps(self.get_cluster_state()))

                elif msg_type == "start_training":
                    if not self.is_training:
                        self.training_task = asyncio.create_task(self.training_loop())

                elif msg_type == "stop_training":
                    self.is_training = False

                elif msg_type == "generate_text":
                    prompt = data.get("prompt", "Hola")
                    generated = self.model.generate(self.tokenizer, prompt, max_new_tokens=50)
                    await send_ws_text(
                        writer,
                        json.dumps({"type": "generated_text", "text": generated}),
                    )
        except Exception:
            pass
        finally:
            if worker_id and worker_id in self.workers:
                del self.workers[worker_id]
                await self.broadcast_dashboards(self.get_cluster_state())
            if is_dashboard and writer in self.dashboards:
                self.dashboards.remove(writer)

    async def start(self) -> asyncio.Server:
        server = await asyncio.start_server(self.handle_client, self.host, self.port)
        ip = get_local_ip()
        print(f"[Redmi 9C Director] Dashboard & Cluster Server running at http://{ip}:{self.port}")
        print(f"[Redmi 9C Director] Esperando conexión de Redmi 13C en ws://{ip}:{self.port}/ws")
        return server


async def main() -> None:
    parser = argparse.ArgumentParser(description="MiniMind Director (Redmi 9C)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--data", default=DEFAULT_DATA)
    args = parser.parse_args()

    director = DirectorServer(host=args.host, port=args.port, data_path=args.data)
    server = await director.start()
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
