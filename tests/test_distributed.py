"""
End-to-end verification test for Distributed MiniMind Training
(Redmi 9C Director + Redmi 13C Worker).
"""

import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minimind_cluster.director import DirectorServer
from minimind_cluster.worker import run_worker


async def main() -> None:
    port = 18080
    director = DirectorServer(host="127.0.0.1", port=port)
    server = await director.start()

    worker_task = asyncio.create_task(
        run_worker("127.0.0.1", port, "Redmi 13C (Worker)", "redmi-13c-test")
    )

    # Wait for Redmi 13C worker to register
    for _ in range(50):
        if "redmi-13c-test" in director.workers:
            break
        await asyncio.sleep(0.05)

    assert "redmi-13c-test" in director.workers, "Redmi 13C worker failed to register!"
    print("[TEST] Redmi 13C worker registered successfully.")

    # Run 12 distributed training steps
    await director.training_loop(max_steps=12)

    assert director.model.step_count == 12, f"Expected 12 steps, got {director.model.step_count}"
    assert director.workers["redmi-13c-test"].steps_completed == 12, "Worker did not complete all 12 steps!"

    first_loss = director.loss_history[0]["loss"]
    last_loss = director.loss_history[-1]["loss"]
    print(f"[TEST] Loss went from {first_loss:.4f} -> {last_loss:.4f} over 12 distributed steps.")
    assert last_loss < first_loss, f"Expected loss to decrease, but {first_loss} -> {last_loss}"

    sample = director.model.generate(director.tokenizer, "Hola ", max_new_tokens=20)
    print(f"[TEST] Generated text sample: {sample!r}")
    assert len(sample) > 5

    worker_task.cancel()
    server.close()
    await server.wait_closed()
    print("[TEST] ALL END-TO-END DISTRIBUTED TRAINING CHECKS PASSED!")


if __name__ == "__main__":
    asyncio.run(main())
