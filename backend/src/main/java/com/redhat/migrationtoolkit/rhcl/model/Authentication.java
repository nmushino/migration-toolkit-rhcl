package com.redhat.migrationtoolkit.rhcl.model;

public class Authentication {
    public String type; // apiKey, jwt, oidc, none
    public String location; // header, query
    public String paramName;
    public String oidcIssuerEndpoint;
    public String credentials;
}
