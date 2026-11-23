#!/usr/bin/env python3
import base64
import fcntl
import json
import os
import pty
import select
import signal
import struct
import sys
import termios


def set_winsize(fd: int, rows: int, cols: int) -> None:
    winsize = struct.pack("HHHH", rows, cols, 0, 0)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)


def main() -> None:
    cwd = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    shell = os.environ.get("SHELL", "/bin/bash")
    if not os.path.exists(shell):
        shell = "/bin/sh"

    pid, master_fd = pty.fork()
    if pid == 0:
        try:
            os.chdir(cwd)
        except Exception:
            pass
        os.environ["TERM"] = "xterm-256color"
        os.environ["COLORTERM"] = "truecolor"
        os.execvp(shell, [shell, "-i"])
        sys.exit(1)

    set_winsize(master_fd, 24, 80)

    # Set stdin and master_fd non-blocking
    stdin_fd = sys.stdin.fileno()
    for fd in (master_fd, stdin_fd):
        flags = fcntl.fcntl(fd, fcntl.F_GETFL)
        fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

    stdin_buffer = b""
    running = True

    def handle_sigterm(_signum, _frame):
        nonlocal running
        running = False
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    signal.signal(signal.SIGTERM, handle_sigterm)

    while running:
        try:
            rlist, _, _ = select.select([master_fd, stdin_fd], [], [], 0.1)
        except (InterruptedError, ValueError):
            break

        if master_fd in rlist:
            try:
                data = os.read(master_fd, 8192)
                if not data:
                    break
                payload = json.dumps({
                    "type": "output",
                    "data": base64.b64encode(data).decode("ascii"),
                })
                sys.stdout.write(payload + "\n")
                sys.stdout.flush()
            except OSError:
                break

        if stdin_fd in rlist:
            try:
                chunk = os.read(stdin_fd, 8192)
                if not chunk:
                    break
                stdin_buffer += chunk
                while b"\n" in stdin_buffer:
                    line, stdin_buffer = stdin_buffer.split(b"\n", 1)
                    if not line.strip():
                        continue
                    try:
                        msg = json.loads(line.decode("utf-8"))
                        mtype = msg.get("type")
                        if mtype == "input":
                            raw = base64.b64decode(msg.get("data", ""))
                            os.write(master_fd, raw)
                        elif mtype == "resize":
                            cols = max(10, int(msg.get("cols", 80)))
                            rows = max(4, int(msg.get("rows", 24)))
                            set_winsize(master_fd, rows, cols)
                    except Exception:
                        pass
            except BlockingIOError:
                pass
            except OSError:
                break

        # Check if child exited
        try:
            wpid, status = os.waitpid(pid, os.WNOHANG)
            if wpid == pid:
                code = os.waitstatus_to_exitcode(status)
                sys.stdout.write(json.dumps({"type": "exit", "code": code}) + "\n")
                sys.stdout.flush()
                break
        except ChildProcessError:
            break

    try:
        os.close(master_fd)
    except OSError:
        pass


if __name__ == "__main__":
    main()
