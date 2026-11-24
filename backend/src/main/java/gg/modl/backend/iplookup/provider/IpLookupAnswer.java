package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpFacts;

public sealed interface IpLookupAnswer {
    record Found(IpFacts facts) implements IpLookupAnswer {
    }

    record Refused(String reason) implements IpLookupAnswer {
    }

    record Failed(String reason) implements IpLookupAnswer {
    }
}
