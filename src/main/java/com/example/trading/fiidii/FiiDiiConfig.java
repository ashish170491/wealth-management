package com.example.trading.fiidii;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for FII/DII tracking and analysis.
 */
@Component
@ConfigurationProperties(prefix = "trading.fiidii")
@Data
public class FiiDiiConfig {

    /**
     * Enable/disable FII/DII tracking.
     */
    private boolean enabled = true;

    /**
     * NSE API base URL.
     */
    private String nseBaseUrl = "https://www.nseindia.com";

    /**
     * Threshold for heavy FII selling alert (in Crores).
     * If FII net selling exceeds this, trigger alert.
     */
    private double heavySellingThreshold = 3000.0;

    /**
     * Threshold for heavy FII buying alert (in Crores).
     */
    private double heavyBuyingThreshold = 3000.0;

    /**
     * Minimum deal value to be considered significant (in Crores).
     */
    private double significantDealThreshold = 50.0;

    /**
     * Number of days for trend analysis.
     */
    private int trendAnalysisDays = 5;

    /**
     * Send email alerts.
     */
    private boolean sendAlerts = true;

    /**
     * Sector mapping for stocks.
     * Maps stock symbols to their sectors.
     */
    private Map<String, String> sectorMapping = new HashMap<>();

    /**
     * Known FII client name patterns.
     */
    private List<String> fiiPatterns = List.of(
            "MORGAN STANLEY", "GOLDMAN SACHS", "JP MORGAN", "CITIGROUP",
            "CREDIT SUISSE", "UBS", "BARCLAYS", "HSBC", "NOMURA",
            "CLSA", "MACQUARIE", "BNP PARIBAS", "SOCIETE GENERALE",
            "DEUTSCHE BANK", "MERRILL LYNCH", "JEFFERIES", "BOFA",
            "FII", "FOREIGN", "OFFSHORE", "MAURITIUS", "SINGAPORE",
            "CAYMAN", "LUXEMBOURG", "IRELAND", "ABERDEEN", "BLACKROCK",
            "VANGUARD", "FIDELITY", "TEMPLETON", "CAPITAL GROUP"
    );

    /**
     * Known DII client name patterns.
     */
    private List<String> diiPatterns = List.of(
            "LIC", "SBI", "HDFC", "ICICI PRUDENTIAL", "KOTAK",
            "AXIS", "NIPPON", "UTI", "ADITYA BIRLA", "DSP",
            "TATA", "RELIANCE MUTUAL", "SUNDARAM", "MOTILAL OSWAL",
            "EDELWEISS", "MIRAE", "IDFC", "PGIM", "INVESCO",
            "MUTUAL FUND", "INSURANCE", "PENSION", "PF ", "PROVIDENT",
            "EPFO", "NPS", "GRATUITY"
    );

    /**
     * Default sector mapping for Nifty 50 + major stocks.
     */
    public Map<String, String> getDefaultSectorMapping() {
        Map<String, String> mapping = new HashMap<>();
        
        // Banking & Financial Services
        mapping.put("HDFCBANK", "Banking");
        mapping.put("ICICIBANK", "Banking");
        mapping.put("SBIN", "Banking");
        mapping.put("KOTAKBANK", "Banking");
        mapping.put("AXISBANK", "Banking");
        mapping.put("INDUSINDBK", "Banking");
        mapping.put("BANKBARODA", "Banking");
        mapping.put("PNB", "Banking");
        mapping.put("FEDERALBNK", "Banking");
        mapping.put("IDFCFIRSTB", "Banking");
        mapping.put("BANDHANBNK", "Banking");
        mapping.put("AUBANK", "Banking");
        
        // NBFC & Financial Services
        mapping.put("BAJFINANCE", "NBFC");
        mapping.put("BAJAJFINSV", "NBFC");
        mapping.put("HDFCLIFE", "Insurance");
        mapping.put("SBILIFE", "Insurance");
        mapping.put("ICICIPRULI", "Insurance");
        mapping.put("ICICIGI", "Insurance");
        mapping.put("CHOLAFIN", "NBFC");
        mapping.put("SHRIRAMFIN", "NBFC");
        mapping.put("M&MFIN", "NBFC");
        mapping.put("MUTHOOTFIN", "NBFC");
        
        // IT Services
        mapping.put("TCS", "IT");
        mapping.put("INFY", "IT");
        mapping.put("HCLTECH", "IT");
        mapping.put("WIPRO", "IT");
        mapping.put("TECHM", "IT");
        mapping.put("LTIM", "IT");
        mapping.put("PERSISTENT", "IT");
        mapping.put("COFORGE", "IT");
        mapping.put("MPHASIS", "IT");
        mapping.put("LTTS", "IT");
        
        // Oil & Gas
        mapping.put("RELIANCE", "Oil & Gas");
        mapping.put("ONGC", "Oil & Gas");
        mapping.put("BPCL", "Oil & Gas");
        mapping.put("IOC", "Oil & Gas");
        mapping.put("GAIL", "Oil & Gas");
        mapping.put("HINDPETRO", "Oil & Gas");
        mapping.put("PETRONET", "Oil & Gas");
        mapping.put("OIL", "Oil & Gas");
        
        // Metals & Mining
        mapping.put("TATASTEEL", "Metals");
        mapping.put("JSWSTEEL", "Metals");
        mapping.put("HINDALCO", "Metals");
        mapping.put("VEDL", "Metals");
        mapping.put("COALINDIA", "Metals");
        mapping.put("NMDC", "Metals");
        mapping.put("SAIL", "Metals");
        mapping.put("JINDALSTEL", "Metals");
        mapping.put("NATIONALUM", "Metals");
        
        // Automobile
        mapping.put("MARUTI", "Auto");
        mapping.put("TATAMOTORS", "Auto");
        mapping.put("M&M", "Auto");
        mapping.put("BAJAJ-AUTO", "Auto");
        mapping.put("HEROMOTOCO", "Auto");
        mapping.put("EICHERMOT", "Auto");
        mapping.put("ASHOKLEY", "Auto");
        mapping.put("TVSMOTOR", "Auto");
        
        // Auto Ancillary
        mapping.put("BOSCHLTD", "Auto Ancillary");
        mapping.put("MOTHERSON", "Auto Ancillary");
        mapping.put("BALKRISIND", "Auto Ancillary");
        mapping.put("BHARATFORG", "Auto Ancillary");
        mapping.put("EXIDEIND", "Auto Ancillary");
        mapping.put("APOLLOTYRE", "Auto Ancillary");
        mapping.put("MRF", "Auto Ancillary");
        
        // Pharma & Healthcare
        mapping.put("SUNPHARMA", "Pharma");
        mapping.put("DRREDDY", "Pharma");
        mapping.put("CIPLA", "Pharma");
        mapping.put("DIVISLAB", "Pharma");
        mapping.put("APOLLOHOSP", "Healthcare");
        mapping.put("FORTIS", "Healthcare");
        mapping.put("MAXHEALTH", "Healthcare");
        mapping.put("BIOCON", "Pharma");
        mapping.put("LUPIN", "Pharma");
        mapping.put("AUROPHARMA", "Pharma");
        mapping.put("TORNTPHARM", "Pharma");
        mapping.put("ZYDUSLIFE", "Pharma");
        
        // FMCG
        mapping.put("HINDUNILVR", "FMCG");
        mapping.put("ITC", "FMCG");
        mapping.put("NESTLEIND", "FMCG");
        mapping.put("BRITANNIA", "FMCG");
        mapping.put("DABUR", "FMCG");
        mapping.put("MARICO", "FMCG");
        mapping.put("GODREJCP", "FMCG");
        mapping.put("COLPAL", "FMCG");
        mapping.put("TATACONSUM", "FMCG");
        mapping.put("VBL", "FMCG");
        mapping.put("UNITED SPIRITS", "FMCG");
        
        // Telecom
        mapping.put("BHARTIARTL", "Telecom");
        mapping.put("IDEA", "Telecom");
        
        // Power & Utilities
        mapping.put("NTPC", "Power");
        mapping.put("POWERGRID", "Power");
        mapping.put("TATAPOWER", "Power");
        mapping.put("ADANIPOWER", "Power");
        mapping.put("ADANIGREEN", "Power");
        mapping.put("NHPC", "Power");
        mapping.put("SJVN", "Power");
        mapping.put("TORNTPOWER", "Power");
        mapping.put("CESC", "Power");
        mapping.put("JSW ENERGY", "Power");
        
        // Infrastructure & Construction
        mapping.put("LT", "Infrastructure");
        mapping.put("ADANIENT", "Infrastructure");
        mapping.put("ADANIPORTS", "Infrastructure");
        mapping.put("ULTRACEMCO", "Cement");
        mapping.put("GRASIM", "Cement");
        mapping.put("SHREECEM", "Cement");
        mapping.put("AMBUJACEM", "Cement");
        mapping.put("ACC", "Cement");
        mapping.put("DALBHARAT", "Cement");
        
        // Realty
        mapping.put("DLF", "Realty");
        mapping.put("GODREJPROP", "Realty");
        mapping.put("OBEROIRLTY", "Realty");
        mapping.put("PRESTIGE", "Realty");
        mapping.put("LODHA", "Realty");
        mapping.put("BRIGADE", "Realty");
        mapping.put("SOBHA", "Realty");
        mapping.put("PHOENIXLTD", "Realty");
        
        // Capital Goods
        mapping.put("SIEMENS", "Capital Goods");
        mapping.put("ABB", "Capital Goods");
        mapping.put("HAVELLS", "Capital Goods");
        mapping.put("BHEL", "Capital Goods");
        mapping.put("CUMMINSIND", "Capital Goods");
        mapping.put("THERMAX", "Capital Goods");
        mapping.put("CROMPTON", "Capital Goods");
        mapping.put("VOLTAS", "Capital Goods");
        mapping.put("BLUESTARCO", "Capital Goods");
        
        // Chemicals
        mapping.put("PIDILITIND", "Chemicals");
        mapping.put("SRF", "Chemicals");
        mapping.put("ATUL", "Chemicals");
        mapping.put("DEEPAKNTR", "Chemicals");
        mapping.put("NAVINFLUOR", "Chemicals");
        mapping.put("CLEAN SCIENCE", "Chemicals");
        
        // Consumer Durables
        mapping.put("TITAN", "Consumer Durables");
        mapping.put("BATAINDIA", "Consumer Durables");
        mapping.put("PAGEIND", "Consumer Durables");
        mapping.put("RELAXO", "Consumer Durables");
        mapping.put("RAJESHEXPO", "Consumer Durables");
        mapping.put("WHIRLPOOL", "Consumer Durables");
        
        // Media & Entertainment
        mapping.put("ZEEL", "Media");
        mapping.put("PVR", "Media");
        mapping.put("SUNTV", "Media");
        
        // Retail
        mapping.put("DMART", "Retail");
        mapping.put("TRENT", "Retail");
        mapping.put("ABFRL", "Retail");
        mapping.put("SHOPERSTOP", "Retail");
        
        // Defence
        mapping.put("HAL", "Defence");
        mapping.put("BEL", "Defence");
        mapping.put("BDL", "Defence");
        mapping.put("COCHINSHIP", "Defence");
        mapping.put("MAZAGON", "Defence");
        
        // Railways
        mapping.put("IRCTC", "Railways");
        mapping.put("IRFC", "Railways");
        mapping.put("RVNL", "Railways");
        mapping.put("TITAGARH", "Railways");
        
        return mapping;
    }
}
