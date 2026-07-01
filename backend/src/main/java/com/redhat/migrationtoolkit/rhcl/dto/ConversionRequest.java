package com.redhat.migrationtoolkit.rhcl.dto;

import java.util.List;

public class ConversionRequest {
    public String threescaleUrl;
    public String accessToken;
    public String tenant;
    public String namespace;
    public List<String> serviceIds;
    /** 外部バックエンドURL (例: https://foo.ecs.us-east-2.on.aws/api)。
     * 指定時は ServiceEntry + DestinationRule + Host rewrite を生成する。 */
    public String externalBackendUrl;
}
