package com.bloxbean.cardano.yano.app.api.addresses;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.app.api.addresses.dto.AddressSummaryDto;
import com.bloxbean.cardano.yano.app.api.addresses.dto.AddressTxDto;
import com.bloxbean.cardano.yano.app.api.utxos.dto.AmountDto;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wallet-facing address APIs (ADR-033 M2): transaction history backed by the
 * delta-tracked address-tx index, and a Blockfrost-shaped address summary
 * aggregated from unspent UTXOs.
 *
 * <p>Class-level path stays {@code /} (like UtxoResource): a class path of
 * {@code addresses} would out-match UtxoResource's class path for
 * {@code /addresses/{address}/utxos} and 404 it.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class AddressResource {
    private static final int SUMMARY_PAGE_SIZE = 100;
    private static final int SUMMARY_MAX_PAGES = 100;

    @Inject
    LedgerQuery ledgerQuery;

    @Inject
    HistoryArchiveService historyArchive;

    private AccountHistoryProvider historyProvider() {
        if (historyArchive != null && historyArchive.enabled()) return historyArchive.accountHistoryProvider();
        return ledgerQuery.getAccountHistoryProvider();
    }

    @GET
    @Path("/addresses/{address}/transactions")
    public Response getAddressTransactions(@PathParam("address") String address,
                                           @QueryParam("page") @DefaultValue("1") int page,
                                           @QueryParam("count") @DefaultValue("20") int count,
                                           @QueryParam("order") @DefaultValue("asc") String order,
                                           @QueryParam("use_payment_credential") @DefaultValue("false")
                                           boolean usePaymentCredential) {
        AccountHistoryProvider history = historyProvider();
        if (history == null || !history.isEnabled() || !history.isAddressTxEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Address transaction history disabled "
                            + "(enable yano.history and address-transactions dataset)"))
                    .build();
        }
        String resolvedOrder = "desc".equalsIgnoreCase(order) ? "desc"
                : "asc".equalsIgnoreCase(order) ? "asc" : null;
        if (resolvedOrder == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "order must be asc or desc"))
                    .build();
        }
        int safePage = Math.max(1, page);
        int safeCount = count <= 0 ? 20 : Math.min(count, 100);

        List<AddressTxDto> body = history
                .getAddressTransactionsForAddress(address, usePaymentCredential, safePage, safeCount, resolvedOrder)
                .stream()
                .map(r -> new AddressTxDto(r.txHash(), r.txIdx(), r.blockNo(),
                        ledgerQuery.slotToUnixTime(r.slot()), r.slot()))
                .toList();
        return Response.ok(body).build();
    }

    @GET
    @Path("/addresses/{address}")
    public Response getAddressSummary(@PathParam("address") String address) {
        UtxoState utxoState = ledgerQuery.getUtxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "UTXO state disabled"))
                    .build();
        }

        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        amounts.put("lovelace", BigInteger.ZERO);
        boolean any = false;
        boolean complete = false;
        for (int page = 1; page <= SUMMARY_MAX_PAGES; page++) {
            List<Utxo> utxos = utxoState.getUtxosByAddress(address, page, SUMMARY_PAGE_SIZE);
            if (utxos == null || utxos.isEmpty()) {
                complete = true;
                break;
            }
            any = true;
            for (Utxo utxo : utxos) {
                amounts.merge("lovelace", utxo.lovelace() == null ? BigInteger.ZERO : utxo.lovelace(),
                        BigInteger::add);
                if (utxo.assets() != null) {
                    utxo.assets().forEach(asset -> amounts.merge(
                            asset.policyId() + asset.assetName(), asset.quantity(), BigInteger::add));
                }
            }
            if (utxos.size() < SUMMARY_PAGE_SIZE) {
                complete = true;
                break;
            }
        }
        if (any && !complete) {
            // Never return a silently-truncated balance as authoritative.
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Address holds more than "
                            + (SUMMARY_MAX_PAGES * SUMMARY_PAGE_SIZE)
                            + " UTXOs; aggregate via the paged /addresses/{address}/utxos endpoint"))
                    .build();
        }

        String stakeAddress = null;
        String type = "shelley";
        boolean script = false;
        try {
            Address parsed = new Address(address);
            script = parsed.getPaymentCredential()
                    .map(credential -> credential.getType() == CredentialType.Script)
                    .orElse(false);
            if (parsed.getAddressType() == AddressType.Base) {
                stakeAddress = AddressProvider.getStakeAddress(parsed).toBech32();
            }
        } catch (Exception e) {
            type = "byron";
        }

        // Blockfrost 404s for a never-seen address; without full history we only
        // know "no unspent UTXO now", which is the same signal a wallet needs.
        if (!any) {
            AccountHistoryProvider history = historyProvider();
            boolean everUsed = history != null && history.isEnabled() && history.isAddressTxEnabled()
                    && !history.getAddressTransactionsForAddress(address, false, 1, 1, "asc").isEmpty();
            if (!everUsed) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Address not found",
                                "status_code", 404,
                                "message", "The requested component has not been found."))
                        .build();
            }
        }

        List<AmountDto> amountDtos = new ArrayList<>();
        amounts.forEach((unit, quantity) -> amountDtos.add(new AmountDto(unit, quantity.toString())));
        return Response.ok(new AddressSummaryDto(address, amountDtos, stakeAddress, type, script)).build();
    }
}
