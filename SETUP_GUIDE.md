# Setup and Usage Guide: Automated Stock Trading System

This guide provides step-by-step instructions on how to configure and run the Automated Stock Trading System, including details on obtaining Zerodha Kite Connect credentials.

---

## 1. Prerequisites
- **Java 21** or higher.
- **Maven 3.8+**.
- **Zerodha Kite Connect Account** (See section 2).

---

## 2. Obtaining Zerodha Credentials
To use this application with real market data or for live trading, you need a Zerodha Kite Connect developer account.

1.  **Register as a Developer**: 
    - Visit [Kite Connect](https://kite.trade) and create an account.
    - Note: Kite Connect usually involves a monthly subscription fee (approx. ₹2000 for the base API and ₹2000 for historical data).
2.  **Create a New App**:
    - Log in to the Kite Connect dashboard.
    - Click on **"Create New App"**.
    - Provide an App Name and your **Redirect URL** (e.g., `127.0.0.1` if you don't have a specific callback URL).
3.  **Get API Key and Secret**:
    - Once the app is created, you will see your **API Key** and **API Secret**. 
    - **Never share your API Secret.**
4.  **Generate Access Token**:
    - Launch your app's Login URL in a browser.
    - Authorize the app with your Zerodha credentials.
    - Zerodha will redirect you to your chosen redirect URL with a `request_token` in the query parameters.
    - Use the Kite Connect API (or simple tools like Postman) to exchange this `request_token` for a permanent **Access Token**.
    - Note: The `access_token` expires every morning at around 08:30 IST. You will need to regenerate it daily.

### 3. Automated Token Management (New)
To enable 100% automation, you must provide your Zerodha login credentials and your **TOTP Secret**.

> [!IMPORTANT]
> **TOTP Secret vs. TOTP Code**
> - **TOTP Code**: The 6-digit number (e.g., `123456`) that changes every 30 seconds. **DO NOT** put this in your configuration.
> - **TOTP Secret**: A static base32 string (e.g., `JBSWY3DPEHPK3PXP`) that Zerodha gives you when you first set up External TOTP. This secret is what the app uses to generate the 6-digit codes automatically.

#### How to get your TOTP Secret from Zerodha:
1. Log in to [Kite Web](https://kite.zerodha.com).
2. Go to **My Profile** > **Password & Security**.
3. Click on **"Enable External TOTP"** (or "Disable" and then "Enable" again if you've already set it up).
4. You will see a QR Code. **Look for a link below/near the QR code that says "Can't scan? Copy key" or "Show secret".**
5. Copy that alphanumeric string. That is your `totp-secret`.
6. Paste it into your `application.yml` or set it as `KITE_TOTP_SECRET`.

---

## 4. Configuration

Open `src/main/resources/application.yml` and fill in your details, or set them as environment variables (recommended for security).

### Mandatory Credentials
```yaml
broker:
  kite:
    api-key: "YOUR_API_KEY"
    api-secret: "YOUR_API_SECRET"
    access-token: "YOUR_ACCESS_TOKEN"
```

### Risk Parameters
Adjust these based on your risk appetite:
```yaml
trading:
  risk:
    total-capital: 100000.0
    max-risk-per-trade-pct: 0.01  # 1% risk per trade
    max-daily-loss-pct: 0.03      # 3% max daily loss
    max-open-positions: 5
```

### Paper Trading Mode
To test without financial risk, ensure this is enabled:
```yaml
trading:
  execution:
    paper-trading-mode: true
```

---

## 4. How to Use

### Running the Application
Use Maven to compile and run:
```bash
mvn compile
mvn spring-boot:run
```

### Watching the Trading Loop
The system runs a trading loop every minute during Indian market hours (**09:15 to 15:30 IST**).
- Check the console logs. Every log entry is tagged with a `correlationId` (e.g., `5a2b1c3d-NSE:RELIANCE`).
- Search the logs for a specific `correlationId` to see the full journey of a signal.

### Monitoring Trades
By default, the application uses an **H2 In-Memory Database**. 
- You can access the H2 Console at `http://localhost:8080/h2-console`.
- **JDBC URL**: `jdbc:h2:mem:testdb`
- **Username**: `sa`, **Password**: (leave blank)
- Tables available: `trades`, `broker_orders`, `active_positions`, `strategy_execution_logs`.

---

## 5. Deployment Checklist
- [ ] Set `paper-trading-mode: false` only when you are 100% confident in your strategy.
- [ ] Ensure your system clock is synced with IST.
- [ ] Use environment variables for all secrets (`KITE_API_KEY`, `KITE_ACCESS_TOKEN`, etc.).
- [ ] Monitor the `trades` table regularly to verify P&L and execution status.
