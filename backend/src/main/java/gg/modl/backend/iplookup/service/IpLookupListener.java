package gg.modl.backend.iplookup.service;

import gg.modl.backend.iplookup.model.IpLookup;

public interface IpLookupListener {
    void resolved(IpLookup lookup);
}
