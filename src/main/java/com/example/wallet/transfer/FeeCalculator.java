package com.example.wallet.transfer;

import com.example.wallet.config.WalletProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fee rules live in configuration, computed in integer kobo. VAT is charged on the fee, not on the amount. */
@Component
public class FeeCalculator {

    public record Fees(long stampDuty, long fee, long vat) {
        public static final Fees NONE = new Fees(0, 0, 0);

        public long total() {
            return stampDuty + fee + vat;
        }
    }

    private final WalletProperties props;

    public FeeCalculator(WalletProperties props) {
        this.props = props;
    }

    public Fees calculate(long amountKobo) {
        var f = props.fees();
        long stamp = amountKobo >= f.stampDutyThresholdKobo() ? f.stampDutyKobo() : 0;
        long fee = f.flatFeeKobo();
        long vat = BigDecimal.valueOf(fee).multiply(f.vatRate()).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Fees(stamp, fee, vat);
    }
}
