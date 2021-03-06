package de.mas.wiiu.jnus;

import de.mas.wiiu.jnus.entities.Ticket;
import de.mas.wiiu.jnus.implementations.NUSDataProvider;
import de.mas.wiiu.jnus.implementations.NUSDataProviderRemote;

public final class NUSTitleLoaderRemote extends NUSTitleLoader {

    private NUSTitleLoaderRemote() {
        super();
    }

    public static NUSTitle loadNUSTitle(long titleID) throws Exception {
        return loadNUSTitle(titleID, Settings.LATEST_TMD_VERSION, null);
    }

    public static NUSTitle loadNUSTitle(long titleID, int version) throws Exception {
        return loadNUSTitle(titleID, version, null);
    }

    public static NUSTitle loadNUSTitle(long titleID, Ticket ticket) throws Exception {
        return loadNUSTitle(titleID, Settings.LATEST_TMD_VERSION, ticket);
    }

    public static NUSTitle loadNUSTitle(long titleID, int version, Ticket ticket) throws Exception {
        NUSTitleLoader loader = new NUSTitleLoaderRemote();
        NUSTitleConfig config = new NUSTitleConfig();

        config.setVersion(version);
        config.setTitleID(titleID);
        config.setTicket(ticket);

        return loader.loadNusTitle(config);
    }

    @Override
    protected NUSDataProvider getDataProvider(NUSTitle title, NUSTitleConfig config) {
        return new NUSDataProviderRemote(title, config.getVersion(), config.getTitleID());
    }

}
