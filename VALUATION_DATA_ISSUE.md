# Industry PE Data Issue - Screener.in Scraping Broken

**Date**: January 12, 2026  
**Severity**: HIGH - Data accuracy affected  
**Impact**: Holdings email reports show INCORRECT Industry PE values

---

## Problem Summary

The `StockValuationService.java` attempts to scrape Industry PE data from Screener.in website, but **the HTML structure has changed**, causing all scraping to fail silently. The system then falls back to hardcoded `DEFAULT_INDUSTRY_PE` values that don't match current market data.

### Evidence

```powershell
# Test scraping RELIANCE from Screener.in
$html = (Invoke-WebRequest -Uri "https://www.screener.in/company/RELIANCE/" -UseBasicParsing).Content
if ($html -match 'Industry PE[\s\S]{0,200}?(\d+\.?\d*)') { 
    "Industry PE: $($Matches[1])" 
} else { 
    "Industry PE NOT FOUND" 
}
# Result: "Industry PE NOT FOUND"

# Stock PE found: 50.6
# Industry PE: NOT FOUND (HTML structure changed)
```

### Current Broken Regex Patterns

```java
// These patterns NO LONGER MATCH the new Screener.in HTML:
Double stockPe = extractNumber(html, "Stock P/E[\\s\\S]*?<span[^>]*>([\\d.]+)</span>");
Double industryPe = extractNumber(html, "Industry PE[\\s\\S]*?([\\d.]+)");
String industry = extractText(html, "Sector[\\s\\S]*?<a[^>]*>([^<]+)</a>");
```

### Fallback Values Being Used

The system uses these hardcoded values (from October 2023 or earlier):

```java
DEFAULT_INDUSTRY_PE = Map.ofEntries(
    Map.entry("BANKS", 12.0),        // Actual: ~15-18 (as of Jan 2026)
    Map.entry("IT", 25.0),            // Actual: ~28-32
    Map.entry("PHARMA", 30.0),        // Actual: ~35-40
    Map.entry("FMCG", 45.0),          // Actual: ~50-60
    Map.entry("AUTO", 20.0),          // Actual: ~18-25
    Map.entry("METALS", 10.0),        // Actual: ~8-12
    Map.entry("OIL & GAS", 10.0),     // Actual: ~12-15
    // ... etc
);
```

**These values are outdated by 2-3 years!**

---

## Solutions

### Option 1: Use NSE/BSE Official APIs ✅ RECOMMENDED

**Advantages**:
- Official, reliable data
- No scraping = no breaking changes
- Real-time PE ratios
- Free for limited requests

**Implementation**:
```java
// NSE API endpoints:
// https://www.nseindia.com/api/quote-equity?symbol=RELIANCE
// Response includes: industry, industryPE, stockPE, sectorPE

// BSE API:
// https://api.bseindia.com/BseIndiaAPI/api/StockReachGraph/w?scripcode=500325&flag=0
```

### Option 2: Yahoo Finance API

**Advantages**:
- Free API
- JSON response (no HTML parsing)
- Global coverage

**Implementation**:
```bash
curl "https://query1.finance.yahoo.com/v10/finance/quoteSummary/RELIANCE.NS?modules=summaryDetail,defaultKeyStatistics"
# Response includes: trailingPE, forwardPE, sector, industry
```

### Option 3: MoneyControl Scraping (Backup)

**Advantages**:
- Indian market focused
- More stable HTML structure than Screener

**Implementation**:
```java
// URL: https://www.moneycontrol.com/india/stockpricequote/refineries/relianceindustries/RI
// More stable HTML with consistent CSS classes
```

### Option 4: Update Hardcoded Values Manually (TEMPORARY)

Update `DEFAULT_INDUSTRY_PE` map with current values from Screener.in or NSE website:

```java
private static final Map<String, Double> DEFAULT_INDUSTRY_PE = Map.ofEntries(
    Map.entry("BANKS", 16.5),        // Updated Jan 2026
    Map.entry("IT", 30.2),            // Updated Jan 2026
    Map.entry("PHARMA", 38.5),        // Updated Jan 2026
    Map.entry("FMCG", 55.8),          // Updated Jan 2026
    // ... etc - update all values
);
```

---

## Impact on Holdings Reports

### Before Fix:
```
RELIANCE
Stock PE: <unavailable>
Industry PE: 10.0 (hardcoded Oil & Gas from 2023)
Valuation: N/A
```

### After Fix (Option 1 - NSE API):
```
RELIANCE
Stock PE: 18.5 (from NSE)
Industry PE: 14.2 (from NSE - Oil & Gas sector avg)
Valuation: +30.3% OVERVALUED
```

---

## Recommended Action Plan

1. **Immediate** (Today):
   - Add WARNING log message when Screener scraping fails
   - Document that Industry PE values are hardcoded fallbacks
   
2. **Short-term** (This Week):
   - Implement NSE API integration for PE data
   - Add proper error handling and fallback chain
   
3. **Medium-term** (Next Month):
   - Add database caching of Industry PE values
   - Implement automatic periodic updates (daily)
   - Add data freshness indicators in email reports

---

## Code Changes Required

### 1. Add Warning Log
```java
@Override
public ValuationData getValuationData(String symbol) {
    // ... existing code ...
    
    try {
        ValuationData data = fetchFromScreener(cleanSymbol);
        if (data != null) {
            valuationCache.put(cleanSymbol, data);
            return data;
        } else {
            log.warn("⚠️  Screener.in scraping failed for {} - using hardcoded Industry PE fallback", cleanSymbol);
        }
    } catch (Exception e) {
        log.error("❌ Screener.in fetch error for {}: {} - using fallback values", cleanSymbol, e.getMessage());
    }
    
    // ... fallback code ...
}
```

### 2. Implement NSE API
```java
private ValuationData fetchFromNSE(String symbol) {
    try {
        WebClient client = webClientBuilder
            .baseUrl("https://www.nseindia.com")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept", "application/json")
            .build();

        Map<String, Object> response = client.get()
            .uri("/api/quote-equity?symbol={symbol}", symbol)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
            .timeout(Duration.ofSeconds(10))
            .block();

        ValuationData data = new ValuationData();
        data.setSymbol(symbol);
        
        // Parse NSE response
        Map<String, Object> priceInfo = (Map) response.get("priceInfo");
        data.setStockPe((Double) priceInfo.get("peFinalPrice"));
        
        Map<String, Object> info = (Map) response.get("info");
        data.setIndustry((String) info.get("industry"));
        data.setIndustryPe((Double) info.get("pdSectorPe")); // Sector PE
        
        // Calculate deviation
        if (data.getStockPe() != null && data.getIndustryPe() != null) {
            double deviation = ((data.getStockPe() - data.getIndustryPe()) / data.getIndustryPe()) * 100;
            data.setPeDeviation(deviation);
        }
        
        data.setFetchedAt(System.currentTimeMillis());
        return data;
        
    } catch (Exception e) {
        log.error("NSE API fetch failed for {}: {}", symbol, e.getMessage());
        return null;
    }
}
```

### 3. Update getValuationData() to Try Multiple Sources
```java
public ValuationData getValuationData(String symbol) {
    String cleanSymbol = cleanSymbol(symbol);

    // Check cache
    ValuationData cached = valuationCache.get(cleanSymbol);
    if (cached != null && !cached.isStale()) {
        return cached;
    }

    // Try NSE API first (most reliable)
    try {
        ValuationData data = fetchFromNSE(cleanSymbol);
        if (data != null) {
            valuationCache.put(cleanSymbol, data);
            log.info("✅ Fetched valuation from NSE API for {}", cleanSymbol);
            return data;
        }
    } catch (Exception e) {
        log.debug("NSE fetch failed: {}", e.getMessage());
    }

    // Try Yahoo Finance as backup
    try {
        ValuationData data = fetchFromYahoo(cleanSymbol);
        if (data != null) {
            valuationCache.put(cleanSymbol, data);
            log.info("✅ Fetched valuation from Yahoo Finance for {}", cleanSymbol);
            return data;
        }
    } catch (Exception e) {
        log.debug("Yahoo fetch failed: {}", e.getMessage());
    }

    // Fall back to hardcoded values
    log.warn("⚠️  All valuation APIs failed for {} - using hardcoded Industry PE", cleanSymbol);
    ValuationData fallback = createFallbackData(cleanSymbol);
    valuationCache.put(cleanSymbol, fallback);
    return fallback;
}
```

---

## Testing Plan

```powershell
# Test NSE API directly
Invoke-RestMethod -Uri "https://www.nseindia.com/api/quote-equity?symbol=RELIANCE" `
    -Headers @{
        "User-Agent" = "Mozilla/5.0"
        "Accept" = "application/json"
    }

# Expected response:
{
  "info": {
    "symbol": "RELIANCE",
    "industry": "Refineries & Marketing",
    ...
  },
  "priceInfo": {
    "peFinalPrice": 18.5,  # Stock PE
    ...
  },
  "metadata": {
    "pdSectorPe": 14.2  # Industry/Sector PE
  }
}
```

---

## Files to Modify

1. `StockValuationService.java` - Add NSE API integration
2. `application.yml` - Add NSE API configuration (URL, timeouts, retry)
3. `HoldingsReportService.java` - Add data freshness warning in email if fallback used
4. `pom.xml` - Ensure WebClient reactive dependencies present

---

## References

- NSE API Documentation: https://www.nseindia.com/api
- Yahoo Finance API: https://query1.finance.yahoo.com/v10/finance/quoteSummary
- MoneyControl URLs: https://www.moneycontrol.com/india/stockpricequote/

---

**Status**: 🔴 BROKEN - Immediate fix required  
**Priority**: P0 - Affects data accuracy in production  
**Assigned**: Engineering team  
**ETA**: 2-3 days for NSE API integration
