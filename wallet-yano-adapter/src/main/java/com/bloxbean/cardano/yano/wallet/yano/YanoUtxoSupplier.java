package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.blockproducer.UtxoMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class YanoUtxoSupplier implements UtxoSupplier {
    private final UtxoState utxoState;
    private volatile boolean searchByAddressVkh;

    public YanoUtxoSupplier(UtxoState utxoState) {
        this.utxoState = Objects.requireNonNull(utxoState, "utxoState is required");
    }

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        int pageSize = normalizePageSize(nrOfItems);
        int yanoPage = normalizeYanoPage(page);

        List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> yanoUtxos = searchByAddressVkh
                ? utxoState.getUtxosByPaymentCredential(address, yanoPage, pageSize)
                : utxoState.getUtxosByAddress(address, yanoPage, pageSize);

        List<Utxo> cclUtxos = yanoUtxos == null
                ? new ArrayList<>()
                : yanoUtxos.stream()
                        .map(UtxoMapper::toCclUtxo)
                        .filter(Objects::nonNull)
                        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        if (order == OrderEnum.desc) {
            Collections.reverse(cclUtxos);
        }

        return cclUtxos;
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        return utxoState.getUtxo(new Outpoint(txHash, outputIndex))
                .map(UtxoMapper::toCclUtxo);
    }

    @Override
    public boolean isUsedAddress(com.bloxbean.cardano.client.address.Address address) {
        return !getPage(address.toBech32(), 1, 0, OrderEnum.asc).isEmpty();
    }

    @Override
    public void setSearchByAddressVkh(boolean flag) {
        this.searchByAddressVkh = flag;
    }

    private int normalizePageSize(Integer nrOfItems) {
        return nrOfItems == null || nrOfItems <= 0 ? DEFAULT_NR_OF_ITEMS_TO_FETCH : nrOfItems;
    }

    private int normalizeYanoPage(Integer page) {
        return page == null || page < 0 ? 1 : page + 1;
    }
}
