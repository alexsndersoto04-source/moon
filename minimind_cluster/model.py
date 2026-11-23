"""
MiniMind Pure-Python Transformer Implementation
Real forward pass, cross-entropy loss, backpropagation, gradient accumulation,
and AdamW optimizer — zero external dependencies (runs on any Android/Termux Python 3).
"""

import json
import math
import random
from typing import Dict, List, Tuple


class CharTokenizer:
    """Character/subword-level tokenizer for MiniMind training."""

    def __init__(self, text: str = ""):
        chars = sorted(list(set(text))) if text else []
        if "<pad>" not in chars:
            chars = ["<pad>", "<unk>"] + chars
        self.itos: List[str] = chars
        self.stoi: Dict[str, int] = {ch: i for i, ch in enumerate(chars)}

    @property
    def vocab_size(self) -> int:
        return len(self.itos)

    def encode(self, text: str) -> List[int]:
        unk_id = self.stoi.get("<unk>", 1)
        return [self.stoi.get(ch, unk_id) for ch in text]

    def decode(self, ids: List[int]) -> str:
        return "".join(self.itos[i] if 0 <= i < len(self.itos) else "" for i in ids)

    def to_dict(self) -> dict:
        return {"itos": self.itos}

    @classmethod
    def from_dict(cls, data: dict) -> "CharTokenizer":
        tok = cls()
        tok.itos = data["itos"]
        tok.stoi = {ch: i for i, ch in enumerate(tok.itos)}
        return tok


def _zeros(rows: int, cols: int) -> List[List[float]]:
    return [[0.0] * cols for _ in range(rows)]


def _randn(rows: int, cols: int, scale: float = 0.05) -> List[List[float]]:
    return [[random.gauss(0.0, scale) for _ in range(cols)] for _ in range(rows)]


def _softmax(logits: List[float]) -> List[float]:
    m = max(logits)
    exps = [math.exp(x - m) for x in logits]
    s = sum(exps)
    return [e / s for e in exps]


class MiniMindModel:
    """
    Lightweight causal Transformer (MiniMind-Nano):
    - Token + Positional Embeddings
    - Single-Head Causal Self-Attention (Q, K, V, O)
    - Feed-Forward Network (W1, W2 with ReLU)
    - Output LM Head
    """

    def __init__(self, vocab_size: int, d_model: int = 32, d_ff: int = 64, max_seq_len: int = 32):
        self.vocab_size = vocab_size
        self.d_model = d_model
        self.d_ff = d_ff
        self.max_seq_len = max_seq_len

        scale = 1.0 / math.sqrt(d_model)
        self.params: Dict[str, List[List[float]]] = {
            "tok_emb": _randn(vocab_size, d_model, scale),
            "pos_emb": _randn(max_seq_len, d_model, scale),
            "Wq": _randn(d_model, d_model, scale),
            "Wk": _randn(d_model, d_model, scale),
            "Wv": _randn(d_model, d_model, scale),
            "Wo": _randn(d_model, d_model, scale),
            "W1": _randn(d_model, d_ff, scale),
            "W2": _randn(d_ff, d_model, scale),
            "lm_head": _randn(d_model, vocab_size, scale),
        }

        # AdamW state
        self.m: Dict[str, List[List[float]]] = {
            k: _zeros(len(v), len(v[0])) for k, v in self.params.items()
        }
        self.v: Dict[str, List[List[float]]] = {
            k: _zeros(len(v), len(v[0])) for k, v in self.params.items()
        }
        self.step_count = 0

    def get_weights(self) -> Dict[str, List[List[float]]]:
        return self.params

    def set_weights(self, weights: Dict[str, List[List[float]]]) -> None:
        for k, v in weights.items():
            if k in self.params:
                self.params[k] = [row[:] for row in v]

    def forward_backward(
        self, input_ids: List[int], target_ids: List[int]
    ) -> Tuple[float, Dict[str, List[List[float]]]]:
        """
        Compute real forward pass, cross-entropy loss, and exact gradients via backprop.
        Returns (loss, grads_dict).
        """
        T = len(input_ids)
        D = self.d_model
        F = self.d_ff
        V = self.vocab_size
        P = self.params

        grads: Dict[str, List[List[float]]] = {
            k: _zeros(len(v), len(v[0])) for k, v in P.items()
        }

        # 1. Embeddings: X[t] = tok_emb[input_ids[t]] + pos_emb[t]
        X = []
        for t in range(T):
            tid = input_ids[t]
            pid = t % self.max_seq_len
            X.append([P["tok_emb"][tid][d] + P["pos_emb"][pid][d] for d in range(D)])

        # 2. Causal Self-Attention
        Q = [[sum(X[t][i] * P["Wq"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]
        K = [[sum(X[t][i] * P["Wk"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]
        V_mat = [[sum(X[t][i] * P["Wv"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]

        inv_sqrt_d = 1.0 / math.sqrt(D)
        attn_weights = []
        attn_out = []
        for t in range(T):
            scores = []
            for s in range(t + 1):
                dot = sum(Q[t][d] * K[s][d] for d in range(D)) * inv_sqrt_d
                scores.append(dot)
            w = _softmax(scores)
            attn_weights.append(w)
            ctx = [sum(w[s] * V_mat[s][d] for s in range(t + 1)) for d in range(D)]
            attn_out.append(ctx)

        # Output projection + residual
        H1 = []
        for t in range(T):
            proj = [sum(attn_out[t][i] * P["Wo"][i][j] for i in range(D)) for j in range(D)]
            H1.append([X[t][d] + proj[d] for d in range(D)])

        # 3. Feed-Forward Network + residual
        ff_pre = []
        ff_act = []
        H2 = []
        for t in range(T):
            pre = [sum(H1[t][i] * P["W1"][i][f] for i in range(D)) for f in range(F)]
            act = [max(0.0, pre[f]) for f in range(F)]
            out = [sum(act[f] * P["W2"][f][d] for f in range(F)) for d in range(D)]
            ff_pre.append(pre)
            ff_act.append(act)
            H2.append([H1[t][d] + out[d] for d in range(D)])

        # 4. LM Head + Cross-Entropy Loss
        total_loss = 0.0
        dH2 = _zeros(T, D)
        for t in range(T):
            logits = [sum(H2[t][d] * P["lm_head"][d][v] for d in range(D)) for v in range(V)]
            probs = _softmax(logits)
            tgt = target_ids[t]
            total_loss += -math.log(max(probs[tgt], 1e-12))

            # d_logits = (probs - one_hot(tgt)) / T
            d_logits = [probs[v] / T for v in range(V)]
            d_logits[tgt] -= 1.0 / T

            for d in range(D):
                h_val = H2[t][d]
                acc = 0.0
                for v in range(V):
                    dl = d_logits[v]
                    grads["lm_head"][d][v] += h_val * dl
                    acc += dl * P["lm_head"][d][v]
                dH2[t][d] = acc

        avg_loss = total_loss / T

        # 5. Backprop through FFN + residual
        dH1 = [row[:] for row in dH2]
        for t in range(T):
            d_act = [0.0] * F
            for f in range(F):
                act_f = ff_act[t][f]
                acc = 0.0
                for d in range(D):
                    dh = dH2[t][d]
                    grads["W2"][f][d] += act_f * dh
                    acc += dh * P["W2"][f][d]
                d_act[f] = acc if ff_pre[t][f] > 0.0 else 0.0

            for i in range(D):
                h1_i = H1[t][i]
                acc = 0.0
                for f in range(F):
                    da = d_act[f]
                    grads["W1"][i][f] += h1_i * da
                    acc += da * P["W1"][i][f]
                dH1[t][i] += acc

        # 6. Backprop through Attention + residual
        dX = [row[:] for row in dH1]
        d_attn_out = _zeros(T, D)
        for t in range(T):
            for i in range(D):
                ao_i = attn_out[t][i]
                acc = 0.0
                for j in range(D):
                    dh = dH1[t][j]
                    grads["Wo"][i][j] += ao_i * dh
                    acc += dh * P["Wo"][i][j]
                d_attn_out[t][i] = acc

        dQ = _zeros(T, D)
        dK = _zeros(T, D)
        dV = _zeros(T, D)

        for t in range(T):
            w = attn_weights[t]
            d_w = [0.0] * (t + 1)
            for s in range(t + 1):
                for d in range(D):
                    d_w[s] += d_attn_out[t][d] * V_mat[s][d]
                    dV[s][d] += w[s] * d_attn_out[t][d]

            dot_wdw = sum(w[s] * d_w[s] for s in range(t + 1))
            for s in range(t + 1):
                d_score = w[s] * (d_w[s] - dot_wdw) * inv_sqrt_d
                for d in range(D):
                    dQ[t][d] += d_score * K[s][d]
                    dK[s][d] += d_score * Q[t][d]

        for t in range(T):
            for i in range(D):
                x_i = X[t][i]
                for j in range(D):
                    grads["Wq"][i][j] += x_i * dQ[t][j]
                    grads["Wk"][i][j] += x_i * dK[t][j]
                    grads["Wv"][i][j] += x_i * dV[t][j]
                    dX[t][i] += dQ[t][j] * P["Wq"][i][j]
                    dX[t][i] += dK[t][j] * P["Wk"][i][j]
                    dX[t][i] += dV[t][j] * P["Wv"][i][j]

        # 7. Backprop into Embeddings
        for t in range(T):
            tid = input_ids[t]
            pid = t % self.max_seq_len
            for d in range(D):
                grads["tok_emb"][tid][d] += dX[t][d]
                grads["pos_emb"][pid][d] += dX[t][d]

        return avg_loss, grads

    def apply_adamw(
        self,
        grads: Dict[str, List[List[float]]],
        lr: float = 0.01,
        beta1: float = 0.9,
        beta2: float = 0.999,
        eps: float = 1e-8,
        weight_decay: float = 0.01,
    ) -> None:
        """Apply AdamW optimizer step using aggregated gradients."""
        self.step_count += 1
        bc1 = 1.0 - (beta1 ** self.step_count)
        bc2 = 1.0 - (beta2 ** self.step_count)

        for name, param in self.params.items():
            g_mat = grads[name]
            m_mat = self.m[name]
            v_mat = self.v[name]
            for i in range(len(param)):
                for j in range(len(param[0])):
                    g = g_mat[i][j]
                    m_mat[i][j] = beta1 * m_mat[i][j] + (1.0 - beta1) * g
                    v_mat[i][j] = beta2 * v_mat[i][j] + (1.0 - beta2) * (g * g)
                    m_hat = m_mat[i][j] / bc1
                    v_hat = v_mat[i][j] / bc2
                    param[i][j] -= lr * (m_hat / (math.sqrt(v_hat) + eps) + weight_decay * param[i][j])

    def generate(self, tokenizer: CharTokenizer, prompt: str, max_new_tokens: int = 40) -> str:
        """Autoregressive text generation."""
        ids = tokenizer.encode(prompt)
        if not ids:
            ids = [0]
        P = self.params
        D = self.d_model
        F = self.d_ff
        V = self.vocab_size
        inv_sqrt_d = 1.0 / math.sqrt(D)

        for _ in range(max_new_tokens):
            ctx_ids = ids[-self.max_seq_len :]
            T = len(ctx_ids)
            X = [
                [P["tok_emb"][ctx_ids[t]][d] + P["pos_emb"][t][d] for d in range(D)]
                for t in range(T)
            ]
            Q = [[sum(X[t][i] * P["Wq"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]
            K = [[sum(X[t][i] * P["Wk"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]
            V_mat = [[sum(X[t][i] * P["Wv"][i][j] for i in range(D)) for j in range(D)] for t in range(T)]

            t = T - 1
            scores = [sum(Q[t][d] * K[s][d] for d in range(D)) * inv_sqrt_d for s in range(T)]
            w = _softmax(scores)
            attn_out = [sum(w[s] * V_mat[s][d] for s in range(T)) for d in range(D)]
            proj = [sum(attn_out[i] * P["Wo"][i][j] for i in range(D)) for j in range(D)]
            h1 = [X[t][d] + proj[d] for d in range(D)]
            act = [max(0.0, sum(h1[i] * P["W1"][i][f] for i in range(D))) for f in range(F)]
            out = [sum(act[f] * P["W2"][f][d] for f in range(F)) for d in range(D)]
            h2 = [h1[d] + out[d] for d in range(D)]
            logits = [sum(h2[d] * P["lm_head"][d][v] for d in range(D)) for v in range(V)]
            probs = _softmax(logits)
            next_id = max(range(V), key=lambda idx: probs[idx])
            ids.append(next_id)

        return tokenizer.decode(ids)

    def save_checkpoint(self, path: str, tokenizer: CharTokenizer) -> None:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "config": {
                        "vocab_size": self.vocab_size,
                        "d_model": self.d_model,
                        "d_ff": self.d_ff,
                        "max_seq_len": self.max_seq_len,
                    },
                    "step_count": self.step_count,
                    "params": self.params,
                    "tokenizer": tokenizer.to_dict(),
                },
                f,
            )


def average_gradients(grad_list: List[Dict[str, List[List[float]]]]) -> Dict[str, List[List[float]]]:
    """All-Reduce: average gradients across all participating devices (Redmi 9C + Redmi 13C)."""
    n = len(grad_list)
    if n == 1:
        return grad_list[0]
    avg: Dict[str, List[List[float]]] = {}
    for k in grad_list[0]:
        rows = len(grad_list[0][k])
        cols = len(grad_list[0][k][0])
        mat = _zeros(rows, cols)
        for g in grad_list:
            g_k = g[k]
            for i in range(rows):
                for j in range(cols):
                    mat[i][j] += g_k[i][j] / n
        avg[k] = mat
    return avg
