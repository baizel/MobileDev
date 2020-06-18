package com.baizelmathew.spotifycontroller.web.router.resource;

import com.baizelmathew.spotifycontroller.web.utils.MIME;

import java.io.InputStream;

public abstract class AbstractResource {
    MIME mime;

    public AbstractResource(MIME mime) {
        this.mime = mime;
    }

    public MIME getMime() {
        return mime;
    }

    public abstract InputStream getResourceInputStream() throws Exception;
}
