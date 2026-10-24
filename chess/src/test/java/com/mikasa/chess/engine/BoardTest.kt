package com.mikasa.chess.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {

    private fun perft(board: Board, depth: Int): Long {
        if (depth == 0) return 1
        val moves = board.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(board.play(it), depth - 1) }
    }

    // Valores de referencia: https://www.chessprogramming.org/Perft_Results
    @Test
    fun perftStartPosition() {
        val b = Board.fromFen(Board.START_FEN)
        assertEquals(20L, perft(b, 1))
        assertEquals(400L, perft(b, 2))
        assertEquals(8902L, perft(b, 3))
        assertEquals(197281L, perft(b, 4))
    }

    @Test
    fun perftKiwipete() {
        val b = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, perft(b, 1))
        assertEquals(2039L, perft(b, 2))
        assertEquals(97862L, perft(b, 3))
    }

    @Test
    fun perftEndgameWithEnPassant() {
        val b = Board.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, perft(b, 1))
        assertEquals(191L, perft(b, 2))
        assertEquals(2812L, perft(b, 3))
        assertEquals(43238L, perft(b, 4))
    }

    @Test
    fun perftPromotions() {
        val b = Board.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44L, perft(b, 1))
        assertEquals(1486L, perft(b, 2))
        assertEquals(62379L, perft(b, 3))
    }

    @Test
    fun perftPosition4() {
        val b = Board.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, perft(b, 1))
        assertEquals(264L, perft(b, 2))
        assertEquals(9467L, perft(b, 3))
    }

    @Test
    fun fenRoundTrip() {
        val fens = listOf(
            Board.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        )
        for (fen in fens) assertEquals(fen, Board.fromFen(fen).toFen())
    }

    @Test
    fun scholarsMate() {
        val b = Board.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
        val move = b.parseUci("h5f7")!!
        assertEquals("Dxf7#", b.san(move))
        assertEquals("Qxf7#", b.san(move, "KQRBN"))
        val after = b.play(move)
        assertTrue(after.isCheckmate())
        assertFalse(after.isStalemate())
    }

    @Test
    fun sanDisambiguationCastlingAndPromotion() {
        val b = Board.fromFen("r3k2r/8/8/8/8/8/1p6/R3K2R b KQkq - 0 1")
        assertEquals("O-O", b.san(b.parseUci("e8g8")!!))
        assertEquals("O-O-O", b.san(b.parseUci("e8c8")!!))
        assertEquals("Td8", b.san(b.parseUci("a8d8")!!))
        assertEquals("bxa1=D+", b.san(b.parseUci("b2a1q")!!))
        assertEquals("b1=C", b.san(b.parseUci("b2b1n")!!))

        val rooks = Board.fromFen("r6r/4k3/8/8/8/8/8/R3K3 b - - 0 1")
        assertEquals("Tad8", rooks.san(rooks.parseUci("a8d8")!!))
        assertEquals("Thd8", rooks.san(rooks.parseUci("h8d8")!!))
        val knights = Board.fromFen("4k3/8/8/1N6/8/1N6/8/4K3 w - - 0 1")
        assertEquals("C5d4", knights.san(knights.parseUci("b5d4")!!))
    }

    @Test
    fun illegalMovesAreRejected() {
        val b = Board.fromFen(Board.START_FEN)
        assertNull(b.parseUci("e2e5"))
        assertNull(b.parseUci("e1e2"))
        assertNull(b.parseUci("zz"))
    }

    @Test
    fun stalemateIsNotMate() {
        val b = Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(b.isStalemate())
        assertFalse(b.isCheckmate())
    }
}
