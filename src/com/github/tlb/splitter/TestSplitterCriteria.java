package com.github.tlb.splitter;

import com.github.tlb.TlbFileResource;

import java.util.List;
import java.io.File;

import com.github.tlb.TlbSuiteFile;
import com.github.tlb.utils.SuiteFileConvertor;
import com.github.tlb.utils.SystemEnvironment;

/**
 * @understands the criteria for splitting a given test suite 
 */
public abstract class TestSplitterCriteria {
    protected File dir;
    protected final SystemEnvironment env;

    protected TestSplitterCriteria(SystemEnvironment env) {
        this.env = env;
    }

    public abstract List<TlbSuiteFile> filterSuites(List<TlbSuiteFile> fileResources);

    public void setDir(File dir) {
        this.dir = dir;
    }
}
