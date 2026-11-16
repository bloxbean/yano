package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parameter update's rational in the script context ({@code ChangedParameters}): its parts are read as CCL's
 * Haskell-strict policy accepts them, so a bignum part ({@code c2}) is a number, reduced, as the ledger's
 * {@code ToPlutusData Rational} has it (CCL ADR 0005 review X8).
 */
class ChangedParametersTest {

    private static Object changedParameters(String update) throws Exception {
        Method method = ConwayTxInfoTranslator.class.getDeclaredMethod("changedParameters", CborSpan.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, CborSpan.of(HexUtil.decodeHexString(update)));
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void aRationalWithBignumPartsIsReduced() throws Exception {
        // {10: 30([2^65, 2^66])} and {10: 30([1, 2])} are the same value
        Object bignum = changedParameters("a10a" + "d81e82c249020000000000000000c249040000000000000000");
        Object plain = changedParameters("a10a" + "d81e820102");
        assertThat(bignum).isEqualTo(plain);
    }
}
