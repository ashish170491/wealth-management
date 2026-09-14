# Intraday Trading Strategies

## Overview

This application implements 4 intraday strategies + 1 swing breakout scanner, all using TA4J indicators with ATR-based risk management.

---

## 1. EMA Crossover Strategy

**Type:** Trend-following
**Timeframe:** 5-minute candles

| Parameter | Value |
|-----------|-------|
| Short EMA | 9 |
| Long EMA | 21 |
| Trend Filter | 50 EMA (0.10% slope threshold) |
| Stop Loss | 2.5x ATR |
| Target | 3:1 Risk-Reward |

**Entry Rules:**
- **BUY:** Short EMA crosses above Long EMA + trend filter confirms uptrend
- **SELL:** Short EMA crosses below Long EMA + trend filter confirms downtrend

---

## 2. VWAP Strategy

**Type:** Institutional momentum
**Timeframe:** 5-minute candles

| Parameter | Value |
|-----------|-------|
| Volume Lookback | 20 bars |
| Volume Multiplier | 1.5x average |
| Min Distance | 0.25% from VWAP |

**Entry Rules:**
- **BUY:** Price crosses above VWAP with volume spike
- **SELL:** Price crosses below VWAP with volume spike

---

## 3. Opening Range Breakout (ORB)

**Type:** Momentum breakout
**Time Window:** 9:15 - 10:30 AM only

| Parameter | Value |
|-----------|-------|
| Opening Range | First 15 minutes (9:15-9:30) |
| Volume Multiplier | 1.5x average |
| Breakout Buffer | 0.3% |
| Stop Loss | Opposite end of opening range |
| Target | 3:1 Risk-Reward |

**Entry Rules:**
- **BUY:** Price breaks above opening range high with volume
- **SELL:** Price breaks below opening range low with volume
- Max 1 trade per symbol per day

---

## 4. RSI Mean Reversion

**Type:** Counter-trend
**Timeframe:** 5-minute candles

| Parameter | Value |
|-----------|-------|
| RSI Period | 14 |
| Oversold | < 25 |
| Overbought | > 75 |
| Stop Loss | 2x ATR |
| Target | 3:1 Risk-Reward |

**Entry Rules:**
- **BUY:** RSI drops below 25 (extreme oversold)
- **SELL:** RSI rises above 75 (extreme overbought)

---

## 5. Breakout Scanner (Swing)

**Type:** Positional (1-2 week holding)
**Universe:** Nifty 200 stocks
**Schedule:** 9:30 AM, 11:30 AM, 1:30 PM, 3:00 PM

| Criteria | Value |
|----------|-------|
| Breakout Level | 20-day high |
| Volume | > 1.5x 20-day average |
| Trend | Price > EMA20 > EMA50 |
| RSI | 50-75 (not overbought) |
| Stop Loss | 2x ATR below entry |
| Target 1 | 3x ATR (book 50%) |
| Target 2 | 5x ATR (book remaining) |

---

## Risk Management

All strategies use:
- **ATR-based stops:** Dynamic stop loss based on volatility
- **3:1 Risk-Reward:** Minimum ratio for all trades
- **Position sizing:** Max 1-2% capital risk per trade
- **Max positions:** Configurable (default: 5)
- **Daily loss limit:** 2% of capital
