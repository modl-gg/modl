package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;

public interface IpLookupClient {
    IpLookupProvider provider();

    IpLookupCall call(String ip, IpLookupAccess access);

    IpLookupAnswer read(int status, String body);
}
