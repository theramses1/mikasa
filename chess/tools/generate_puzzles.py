#!/usr/bin/env python3
"""Genera los puzzles de mate de la app a partir de partidas jugadas por Stockfish.

Cada partida se juega con jugadas semi-aleatorias (para que haya errores y
posiciones tácticas). En cuanto un bando tiene un mate forzado en <= MAX_MATE
jugadas, la posición se verifica con un análisis más profundo:

  * la primera jugada del atacante (y cada jugada intermedia) debe ser la
    ÚNICA que da mate en el número de jugadas pedido;
  * el defensor responde siempre con la defensa más resistente;
  * la última jugada puede ser cualquier mate (la app acepta mates
    alternativos en la jugada final).

Salida (TSV, una línea por puzzle):
  id  fen  jugadas_uci  rating  mate_en  fuente

`fen` es la posición ANTES de la última jugada del rival; la primera jugada de
`jugadas_uci` es esa jugada del rival (la app la anima) y el resto es la
solución, alternando jugador / rival y terminando en mate.

Uso:
  PYTHONPATH=<python-chess> python3 generate_puzzles.py --games 1500 \
      --out ../src/main/assets/puzzles.tsv
  # Fusionar varias tandas (otras semillas) quitando duplicados:
  python3 generate_puzzles.py --merge a.tsv b.tsv --out puzzles.tsv
Requiere python-chess y un binario de Stockfish.
"""

import argparse
import multiprocessing as mp
import random
import sys

import chess
import chess.engine

from famous_games import FAMOUS

MAX_MATE = 4
STOCKFISH = "/usr/games/stockfish"
VALUES = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 0}
BASE_RATING = {1: 650, 2: 1150, 3: 1550, 4: 1850, 5: 2050, 6: 2150, 7: 2250, 8: 2350}

VERIFY = chess.engine.Limit(nodes=2_500_000)


def material(board, color):
    return sum(VALUES[p.piece_type] for p in board.piece_map().values() if p.color == color)


def mate_in(info):
    """Mate en N jugadas (positivo = el bando que juega da mate) o None."""
    score = info["score"].relative
    return score.mate() if score.is_mate() else None


def solve(engine, board, n):
    """Devuelve la línea de solución (lista de Move) para mate en n, o None si
    la posición no es un buen puzzle (solución no única, mate más largo...)."""
    line = []
    b = board.copy()
    for k in range(n, 0, -1):
        infos = engine.analyse(b, VERIFY, multipv=2)
        best = infos[0]
        m = mate_in(best)
        if m is None or m != k:
            return None
        if k > 1 and len(infos) > 1:
            m2 = mate_in(infos[1])
            if m2 is not None and 0 < m2 <= k:
                return None  # dos caminos al mate: puzzle ambiguo
        move = best["pv"][0]
        line.append(move)
        b.push(move)
        if k == 1:
            return line if b.is_checkmate() else None
        # El defensor elige la defensa más larga.
        reply = engine.analyse(b, VERIFY)["pv"][0]
        line.append(reply)
        b.push(reply)
    return None


def is_sacrifice(board, move):
    """La pieza que mueve queda capturable y vale más que lo que captura."""
    piece = board.piece_at(move.from_square)
    captured = board.piece_at(move.to_square)
    gain = VALUES[captured.piece_type] if captured else 0
    after = board.copy()
    after.push(move)
    if after.is_checkmate():
        return False
    attacked = bool(after.attackers(after.turn, move.to_square))
    return attacked and VALUES[piece.piece_type] - gain >= 2


def shallow_depth(engine, board, first):
    """Profundidad mínima a la que el motor ve la primera jugada: medida
    aproximada de lo "escondida" que está."""
    for d in (1, 2, 3, 4, 6, 8):
        info = engine.analyse(board, chess.engine.Limit(depth=d))
        if info["pv"][0] == first and mate_in(info):
            return d
    return 10


def estimate_rating(engine, board, line, n, rnd):
    first = line[0]
    r = BASE_RATING.get(n, 2400)
    if not board.gives_check(first):
        r += 160
    if not board.is_capture(first):
        r += 90
    if is_sacrifice(board, first):
        r += 140
    if first.promotion:
        r += 40
    r += 35 * shallow_depth(engine, board, first)
    r += rnd.randint(-60, 60)
    return max(400, min(2800, int(round(r / 10.0)) * 10))


def pick_move(engine, board, rnd):
    """Jugada semi-aleatoria: casi siempre una de las mejores, a veces un error."""
    infos = engine.analyse(board, chess.engine.Limit(depth=rnd.choice((4, 6, 8))), multipv=4)
    moves = [i["pv"][0] for i in infos if "pv" in i]
    if not moves:
        return None
    r = rnd.random()
    if board.fullmove_number <= 3:
        return rnd.choice(moves)
    if r < 0.62:
        return moves[0]
    if r < 0.93 or len(moves) == 1:
        return rnd.choice(moves[: min(3, len(moves))])
    return rnd.choice(list(board.legal_moves))  # error grueso


def play_game(seed):
    rnd = random.Random(seed)
    engine = chess.engine.SimpleEngine.popen_uci(STOCKFISH)
    engine.configure({"Threads": 1, "Hash": 32})
    results = []
    try:
        board = chess.Board()
        prev = None
        while not board.is_game_over() and board.ply() < 160:
            info = engine.analyse(board, chess.engine.Limit(depth=10))
            m = mate_in(info)
            if prev is not None and m is not None and 1 <= m <= MAX_MATE and len(board.piece_map()) >= 9:
                line = solve(engine, board, m)
                attacker = board.turn
                diff = material(board, attacker) - material(board, not attacker)
                if line and diff <= (6 if m == 1 else 9):
                    before = board.copy()
                    before.pop()
                    rating = estimate_rating(engine, board, line, m, rnd)
                    results.append((before.fen(), [prev] + line, rating, m, ""))
                    break
            move = pick_move(engine, board, rnd)
            if move is None:
                break
            board.push(move)
            prev = move
    finally:
        engine.quit()
    return results


def famous_puzzles():
    """Posiciones de partidas reales célebres, verificadas con el motor."""
    engine = chess.engine.SimpleEngine.popen_uci(STOCKFISH)
    out = []
    try:
        for title, moves_san, start_ply, rating in FAMOUS:
            board = chess.Board()
            played = []
            for san in moves_san.split():
                if san[0].isdigit():
                    continue
                played.append(board.push_san(san))
            if not board.is_checkmate():
                print(f"AVISO: {title}: la partida no termina en mate", file=sys.stderr)
            board = chess.Board()
            for mv in played[: start_ply - 1]:
                board.push(mv)
            before = board.fen()
            setup = played[start_ply - 1]
            board.push(setup)
            info = engine.analyse(board, VERIFY)
            n = mate_in(info)
            if n is None or n <= 0:
                print(f"AVISO: {title}: no hay mate forzado", file=sys.stderr)
                continue
            line = solve(engine, board, n)
            if not line:
                print(f"AVISO: {title}: solución no única (mate en {n})", file=sys.stderr)
                continue
            out.append((before, [setup] + line, rating, n, title))
            print(f"OK {title}: mate en {n}", file=sys.stderr)
    finally:
        engine.quit()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--games", type=int, default=1500)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--procs", type=int, default=mp.cpu_count())
    ap.add_argument("--out", default="puzzles.tsv")
    ap.add_argument("--merge", nargs="+", help="fusiona TSV ya generados en vez de generar")
    args = ap.parse_args()

    if args.merge:
        merge(args.merge, args.out)
        return

    puzzles = famous_puzzles()
    seen = {p[0] for p in puzzles}
    seeds = [args.seed * 100003 + i for i in range(args.games)]
    with mp.Pool(args.procs) as pool:
        for i, res in enumerate(pool.imap_unordered(play_game, seeds, chunksize=4)):
            for p in res:
                key = p[0].rsplit(" ", 2)[0]
                if key not in seen:
                    seen.add(key)
                    puzzles.append(p)
            if i % 50 == 0:
                print(f"{i}/{args.games} partidas, {len(puzzles)} puzzles", file=sys.stderr, flush=True)

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# id\tfen\tmoves\trating\tmate_in\tsource\n")
        for i, (fen, line, rating, n, source) in enumerate(puzzles, 1):
            uci = " ".join(m.uci() for m in line)
            f.write(f"{i}\t{fen}\t{uci}\t{rating}\t{n}\t{source}\n")
    counts = {}
    for p in puzzles:
        counts[p[3]] = counts.get(p[3], 0) + 1
    print(f"Total {len(puzzles)} puzzles: {dict(sorted(counts.items()))}", file=sys.stderr)


def merge(paths, out):
    rows, seen = [], set()
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or not line.strip():
                    continue
                cols = line.rstrip("\n").split("\t")
                key = cols[1].rsplit(" ", 2)[0]
                if key not in seen:
                    seen.add(key)
                    rows.append(cols[1:])
    # Las partidas célebres primero; el resto, en el orden en que se generaron.
    rows.sort(key=lambda c: 0 if c[4] else 1)
    with open(out, "w", encoding="utf-8") as f:
        f.write("# id\tfen\tmoves\trating\tmate_in\tsource\n")
        for i, cols in enumerate(rows, 1):
            f.write(f"{i}\t" + "\t".join(cols) + "\n")
    counts = {}
    for c in rows:
        counts[c[3]] = counts.get(c[3], 0) + 1
    print(f"Total {len(rows)} puzzles: {dict(sorted(counts.items()))}", file=sys.stderr)


if __name__ == "__main__":
    main()
