# V2.4 Quant Engine

The calculator is located at:

`app/src/main/java/com/ramboscan/app/FinanceCalculator.kt`

It produces `CalculationEvidence` with one of two statuses:

- `DETERMINISTIC`: enough inputs were extracted and the arithmetic was computed in Kotlin.
- `INCOMPLETE / DO NOT GUESS`: the question names a calculation but required inputs or contract terms are missing.

`ScannerActivity` computes this evidence before local AI inference. `OnDeviceRamboEngine` places it above the retrieved local knowledge in the prompt and explicitly instructs the model never to contradict deterministic arithmetic.
