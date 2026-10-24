"""Partidas reales (y trampas clásicas) que terminan en mate.

Cada entrada: (título, partida en SAN, ply de la última jugada del rival antes
del puzzle, rating aproximado). El generador reproduce la partida hasta ese
ply y verifica con Stockfish que el mate es forzado y único.
"""

FAMOUS = [
    (
        "Mate del loco (trampa clásica)",
        "1. f3 e5 2. g4 Qh4#",
        3, 450,
    ),
    (
        "Mate del pastor (trampa clásica)",
        "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#",
        6, 500,
    ),
    (
        "Trampa del gambito Englund",
        "1. d4 e5 2. dxe5 Nc6 3. Nf3 Qe7 4. Bf4 Qb4+ 5. Bd2 Qxb2 6. Bc3 Bb4 "
        "7. Qd2 Bxc3 8. Qxc3 Qc1#",
        15, 750,
    ),
    (
        "Legal vs. Saint Brie, París 1750",
        "1. e4 e5 2. Nf3 d6 3. Bc4 Bg4 4. Nc3 g6 5. Nxe5 Bxd1 6. Bxf7+ Ke7 7. Nd5#",
        10, 1100,
    ),
    (
        "Trampa Blackburne-Shilling",
        "1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5 Qg5 5. Nxf7 Qxg2 6. Rf1 Qxe4+ "
        "7. Be2 Nf3#",
        13, 600,
    ),
    (
        "Morphy vs. Duque de Brunswick, París 1858 (la partida de la Ópera)",
        "1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 "
        "7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 "
        "13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8#",
        30, 1250,
    ),
    (
        "Réti vs. Tartakower, Viena 1910",
        "1. e4 c6 2. d4 d5 3. Nc3 dxe4 4. Nxe4 Nf6 5. Qd3 e5 6. dxe5 Qa5+ "
        "7. Bd2 Qxe5 8. O-O-O Nxe4 9. Qd8+ Kxd8 10. Bg5+ Kc7 11. Bd8#",
        16, 1650,
    ),
    (
        "Anderssen vs. Kieseritzky, Londres 1851 (la Inmortal)",
        "1. e4 e5 2. f4 exf4 3. Bc4 Qh4+ 4. Kf1 b5 5. Bxb5 Nf6 6. Nf3 Qh6 "
        "7. d3 Nh5 8. Nh4 Qg5 9. Nf5 c6 10. g4 Nf6 11. Rg1 cxb5 12. h4 Qg6 "
        "13. h5 Qg5 14. Qf3 Ng8 15. Bxf4 Qf6 16. Nc3 Bc5 17. Nd5 Qxb2 18. Bd6 Bxg1 "
        "19. e5 Qxa1+ 20. Ke2 Na6 21. Nxg7+ Kd8 22. Qf6+ Nxf6 23. Be7#",
        40, 1800,
    ),
    (
        "Anderssen vs. Dufresne, Berlín 1852 (la Siempreviva)",
        "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. b4 Bxb4 5. c3 Ba5 6. d4 exd4 7. O-O d3 "
        "8. Qb3 Qf6 9. e5 Qg6 10. Re1 Nge7 11. Ba3 b5 12. Qxb5 Rb8 13. Qa4 Bb6 "
        "14. Nbd2 Bb7 15. Ne4 Qf5 16. Bxd3 Qh5 17. Nf6+ gxf6 18. exf6 Rg8 "
        "19. Rad1 Qxf3 20. Rxe7+ Nxe7 21. Qxd7+ Kxd7 22. Bf5+ Ke8 23. Bd7+ Kf8 "
        "24. Bxe7#",
        40, 2000,
    ),
]
