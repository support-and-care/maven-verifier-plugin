/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.verifier;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.verifier.model.Verifications;
import org.apache.maven.plugins.verifier.model.io.xpp3.VerificationsXpp3Reader;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Verifies the existence or non-existence of files/directories and optionally checks file content against a regular
 * expression.
 *
 * @author <a href="vmassol@apache.org">Vincent Massol</a>
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.INTEGRATION_TEST)
public class VerifierMojo extends AbstractMojo {
    /**
     * Project base directory (prepended to relative file paths).
     */
    @Parameter(property = "basedir", required = true)
    private File basedir;

    /**
     * The file containing the verifications to perform.
     */
    @Parameter(
            property = "verifier.verificationFile",
            defaultValue = "${basedir}/src/test/verifier/verifications.xml",
            required = true)
    private File verificationFile;

    /**
     * Whether the build will fail on verification errors.
     */
    @Parameter(property = "verifier.failOnError", defaultValue = "true", required = true)
    private boolean failOnError;

    private VerificationResultPrinter resultPrinter = new ConsoleVerificationResultPrinter(getLog());

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoExecutionException {
        VerificationResult results = verify();
        resultPrinter.print(results);

        // Fail the build if there are errors
        if (this.failOnError && results.hasFailures()) {
            throw new MojoExecutionException("There are test failures");
        }
    }

    /**
     * @param file the file path of the file to check (can be relative or absolute). If relative
     *            the project's basedir will be prefixed.
     * @return the absolute file path of the file to check
     */
    protected File getAbsoluteFileToCheck(File file) {
        File result = file;
        if (!file.isAbsolute()) {
            result = new File(basedir, file.getPath());
        }
        return result;
    }

    private VerificationResult verify() throws MojoExecutionException {
        VerificationResult results = new VerificationResult();

        try (Reader reader = new FileReader(verificationFile)) {
            VerificationsXpp3Reader xppReader = new VerificationsXpp3Reader();
            Verifications verifications = xppReader.read(reader);

            for (org.apache.maven.plugins.verifier.model.File file : verifications.getFiles()) {
                // Transform the file to check into an absolute path prefixing the basedir if
                // the location is relative
                if (file.getLocation() != null) {
                    file.setLocation(
                            getAbsoluteFileToCheck(new File(file.getLocation())).getPath());
                    verifyFile(file, results);
                } else {
                    throw new MojoExecutionException("Missing <location> element");
                }
            }
        } catch (XmlPullParserException | IOException e) {
            throw new MojoExecutionException("Error while verifying files", e);
        }

        return results;
    }

    private boolean verifyFile(org.apache.maven.plugins.verifier.model.File fileCheck, VerificationResult results)
            throws IOException {
        boolean result;

        result = verifyFileExistence(fileCheck, results);
        if (result && fileCheck.getContains() != null) {
            result = result && verifyFileContent(fileCheck, results);
        }

        return result;
    }

    private boolean verifyFileContent(
            org.apache.maven.plugins.verifier.model.File fileCheck, VerificationResult results) throws IOException {
        boolean result = false;

        getLog().debug("Verifying contents of " + fileCheck.getLocation());

        Pattern pattern = Pattern.compile(fileCheck.getContains());

        // Note: Very inefficient way as we load the whole file in memory. If you have a better
        // idea, please submit it!
        Matcher matcher = pattern.matcher(FileUtils.fileRead(new File(fileCheck.getLocation())));

        if (matcher.find()) {
            result = true;
        } else {
            results.addContentFailure(fileCheck);
        }

        return result;
    }

    private boolean verifyFileExistence(
            org.apache.maven.plugins.verifier.model.File fileCheck, VerificationResult results) {
        boolean result;

        File physicalFile = new File(fileCheck.getLocation());
        if (fileCheck.isExists()) {
            getLog().debug("Verifying existence of " + physicalFile);
            result = physicalFile.exists();
            if (!result) {
                results.addExistenceFailure(fileCheck);
            }
        } else {
            getLog().debug("Verifying absence of " + physicalFile);
            result = !physicalFile.exists();
            if (!result) {
                results.addNonExistenceFailure(fileCheck);
            }
        }

        return result;
    }

    /**
     * @param theBasedir Set the base directory.
     */
    public void setBaseDir(File theBasedir) {
        this.basedir = theBasedir;
    }

    /**
     * @param file Set the file for verification.
     */
    public void setVerificationFile(File file) {
        this.verificationFile = file;
    }

    /**
     * @param printer The verification result printer {@link VerificationResultPrinter}
     */
    public void setVerificationResultPrinter(VerificationResultPrinter printer) {
        this.resultPrinter = printer;
    }

    /**
     * @param failOnError true to fail on error false otherwise.
     */
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }
}
