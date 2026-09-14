# Automated ML Model Training

## Overview

The system now includes **fully automated ML model training** that runs daily to improve trap detection accuracy using real trading data.

## How It Works

### 1. Training Data Collection (Real-Time)
- Every time a signal is generated, the system extracts 37 features and stores them in the database
- When positions are closed, outcomes are labeled as "trap" or "non-trap" based on P&L
- **Trap Definition**: Signal that results in loss (stop-loss hit or negative exit)
- **Non-Trap**: Signal that results in profit

### 2. Automated Training Schedule
**Daily at 3:00 PM IST** (just before market closes at 3:30 PM):
1. Check if sufficient data exists (minimum 100 labeled records)
2. Check if enough new data since last training (minimum 50 new records)
3. Export training data to CSV (`training_data/training_data_YYYYMMDD_HHMMSS.csv`)
4. Backup current model (`models/trap_detector_backup_YYYYMMDD_HHMMSS.json`)
5. Run Python training script (`scripts/train_trap_detector.py`)
6. Reload new model into the running application
7. Log training statistics and model performance

### 3. Model Versioning
- Old models are automatically backed up with timestamps
- You can roll back to previous models if needed
- Training data CSVs are preserved for audit/retraining

---

## Configuration

### Application Properties
```yaml
trading:
  ml:
    trap-detection:
      enabled: true                    # Enable ML trap detection
      collect-training-data: true      # Enable data collection
      model-path: models/trap_detector.json
```

### Automated Training Parameters
Located in `AutomatedModelTrainingService.java`:
- `MIN_TRAINING_RECORDS`: 100 (minimum records to start training)
- `MIN_NEW_RECORDS_FOR_RETRAIN`: 50 (new records needed for retraining)
- Schedule: `0 0 15 * * MON-FRI` (3 PM weekdays, IST timezone)

---

## Manual Training

### Via REST API

#### Check Training Status
```bash
curl http://localhost:8080/api/ml/training/status
```

**Response**:
```json
{
  "enabled": true,
  "lastTrainingTime": "2026-01-06T15:00:00",
  "lastTrainingRecordCount": 150,
  "currentRecordCount": 200,
  "newRecordsSinceLastTraining": 50,
  "readyForTraining": true,
  "shouldRetrain": true,
  "minRecordsRequired": 100,
  "minNewRecordsForRetrain": 50
}
```

#### Trigger Manual Training
```bash
curl -X POST http://localhost:8080/api/ml/training/train-now
```

**Response**:
```json
{
  "success": true,
  "message": "Model training completed successfully",
  "modelLoaded": true,
  "trainingStatus": { ... }
}
```

### Via PowerShell
```powershell
# Check status
Invoke-RestMethod -Uri "http://localhost:8080/api/ml/training/status" | ConvertTo-Json

# Train now
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/ml/training/train-now" | ConvertTo-Json
```

---

## Training Data Pipeline

### Feature Extraction (37 Features)
When a signal is generated, the system extracts:

**Price & Volatility**:
- Current price, ATR, ATR percentage

**Volume**:
- Volume ratio (current/average), OBV slope, volume confirmation

**Technical Indicators**:
- RSI, RSI slope, RSI divergence
- MACD, MACD histogram, MACD slope
- Price vs EMA20/EMA50, EMA crossover
- ADX, trend strength

**Pattern Recognition**:
- VWAP deviation, distance from high/low
- Breakout strength, candle patterns
- Consecutive green/red candles

**Timing**:
- Minute of day, day of week
- First/last hour flags

**Market Context**:
- VIX, market regime (trending/ranging)

### Outcome Labeling
After position closes:
- **P&L %** calculated
- **Exit reason** recorded (stop-loss, target, timeout)
- **Holding time** tracked
- **Max Favorable Excursion (MFE)** computed
- **Labeled as trap** if: P&L < 0 or stop-loss hit

---

## Python Training Script

### Command Line Usage
```bash
cd scripts
python train_trap_detector.py --data ../training_data/training_data_20260106.csv --output ../models/trap_detector.json
```

### What It Does
1. Loads CSV training data
2. Splits 80/20 train/test
3. Trains XGBoost classifier (100 trees, max depth 5)
4. Applies class balancing for imbalanced data
5. Evaluates model (accuracy, precision, recall, AUC-ROC)
6. Exports to JSON format for Java consumption
7. Saves training configuration

### Model Hyperparameters
```python
n_estimators=100        # Number of trees
max_depth=5             # Tree depth
learning_rate=0.1       # Step size
reg_lambda=3            # L2 regularization
scale_pos_weight=auto   # Class imbalance handling
```

---

## Monitoring & Logs

### Training Logs
Look for these patterns in `logs/trading-app.log`:

```
=== AUTOMATED ML MODEL TRAINING CHECK ===
Training data stats: 200 total, 80 traps, 120 non-traps
Starting automated model training with 200 records (50 new since last training)...
Exporting training data to training_data/training_data_20260106_150000.csv...
✅ Exported 45678 bytes to training_data/training_data_20260106_150000.csv
✅ Backed up current model to models/trap_detector_backup_20260106_150000.json
Running Python training script...
[Python] Loading data from training_data/training_data_20260106_150000.csv...
[Python] Parsed 200 valid samples
[Python] Class distribution: Trap=80, Non-trap=120
[Python] Training XGBoost model...
[Python] Test Accuracy: 87.5%
[Python] Test AUC-ROC: 0.92
✅ Python training completed successfully
Reloading ML model...
✅ Model training pipeline completed successfully
✅ Automated model training completed successfully at 2026-01-06T15:05:23
=== AUTOMATED ML MODEL TRAINING CHECK COMPLETE ===
```

### Check Training Data Stats
```bash
curl http://localhost:8080/api/ml/training-data/stats
```

**Response**:
```json
{
  "totalRecords": 200,
  "trapCount": 80,
  "nonTrapCount": 120,
  "avgTrapPnl": -1.2,
  "avgNonTrapPnl": 2.5
}
```

---

## File Structure

```
intraday-app/
├── models/
│   ├── trap_detector.json              # Current active model
│   ├── trap_detector_config.json       # Model config
│   ├── trap_detector_backup_*.json     # Versioned backups
│   └── trap_detector_config_backup_*.json
├── training_data/
│   └── training_data_YYYYMMDD_HHMMSS.csv  # Exported training data
├── scripts/
│   ├── train_trap_detector.py          # Training script
│   └── requirements.txt                # Python dependencies
└── src/main/java/.../ml/
    ├── AutomatedModelTrainingService.java  # Automation logic
    ├── TrainingDataCollector.java          # Data collection
    ├── MLTrapDetectionService.java         # Model inference
    └── MLController.java                   # REST API
```

---

## Model Performance Tracking

### Key Metrics to Monitor
1. **Accuracy**: Overall correct predictions (target: >80%)
2. **Precision**: Trap predictions that are correct (target: >75%)
3. **Recall**: Actual traps correctly identified (target: >85%)
4. **AUC-ROC**: Model's ability to separate classes (target: >0.85)

### Example Python Output
```
Classification Report:
              precision    recall  f1-score   support
           0       0.88      0.90      0.89        24
           1       0.85      0.82      0.83        16

    accuracy                           0.88        40
   macro avg       0.86      0.86      0.86        40
weighted avg       0.87      0.88      0.87        40

Test AUC-ROC: 0.92
```

**Interpretation**:
- 88% accuracy on test set
- 85% precision for trap detection (15% false positives)
- 82% recall for traps (18% missed traps)
- 0.92 AUC-ROC (excellent discrimination)

---

## Troubleshooting

### Issue: Training Skipped (Insufficient Data)
**Log**: `Insufficient training data (50 < 100). Skipping training.`

**Solution**: Wait for more trades to be executed and labeled. System needs 100+ labeled records.

### Issue: Python Script Failed
**Log**: `❌ Python training failed with exit code: 1`

**Possible Causes**:
1. Python dependencies not installed
   - Fix: `cd scripts && pip install -r requirements.txt`
2. Invalid CSV data
   - Check: `training_data/training_data_*.csv` for corruption
3. Insufficient samples in CSV
   - Check: CSV has at least 50 rows

### Issue: Model Not Reloaded
**Log**: `Failed to reload ML model: File not found`

**Solution**: 
- Check if `models/trap_detector.json` exists after training
- Manually reload: `curl -X POST http://localhost:8080/api/ml/model/reload`

### Issue: Class Imbalance Warning
**Log**: `Class imbalance detected (trap ratio: 85%). Training anyway with class weights.`

**Impact**: Model may be biased toward majority class. System automatically applies class weights to compensate.

**Monitoring**: If trap ratio consistently >80% or <20%, consider:
- Adjusting strategy parameters to generate more balanced signals
- Collecting more data before retraining

---

## Best Practices

### 1. Let Initial Data Accumulate
- **First 2 weeks**: Let system collect ~200-300 labeled samples
- **Don't train immediately**: Wait for diverse market conditions (trending, ranging, volatile)

### 2. Monitor Model Drift
- Compare trap detection accuracy week-over-week
- If accuracy drops below 75%, retrain with recent data

### 3. Backup Strategy
- Keep last 3-5 model versions
- If new model performs worse, roll back:
  ```bash
  cp models/trap_detector_backup_20260105_180000.json models/trap_detector.json
  curl -X POST http://localhost:8080/api/ml/model/reload
  ```

### 4. Regular Retraining
- **Weekly**: For active trading (50+ new trades/week)
- **Monthly**: For less active periods
- System automates this if `MIN_NEW_RECORDS_FOR_RETRAIN` threshold met

### 5. Validate New Models
- Check training logs for accuracy metrics
- Monitor trap detection rate after reloading
- Compare P&L improvement vs previous model

---

## Advanced: Custom Training Schedule

To change the training schedule, modify the cron expression in `AutomatedModelTrainingService.java`:

```java
@Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
public void scheduledModelTraining() {
    // ...
}
```

**Cron Examples**:
- `0 0 15 * * MON-FRI` - 3 PM on weekdays (default)
- `0 0 20 * * SUN` - 8 PM every Sunday
- `0 0 18 * * *` - 6 PM daily (including weekends)
- `0 0 18 1 * *` - 6 PM on 1st of every month

---

## Summary

✅ **Automated**: Runs daily at 6 PM without manual intervention  
✅ **Safe**: Backs up old models before training new ones  
✅ **Monitored**: Full logging of training pipeline  
✅ **API-Controlled**: Manual triggers via REST endpoints  
✅ **Data-Driven**: Uses real trading outcomes to improve accuracy  

The system continuously learns from your trading data and improves trap detection over time! 🎯
