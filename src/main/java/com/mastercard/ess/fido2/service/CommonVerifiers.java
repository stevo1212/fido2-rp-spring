/*
 * Copyright (c) 2018 Mastercard
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.mastercard.ess.fido2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mastercard.ess.fido2.ctap.AttestationConveyancePreference;
import com.mastercard.ess.fido2.ctap.UserVerification;
import com.mastercard.ess.fido2.service.processors.AttestationFormatProcessor;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CommonVerifiers {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommonVerifiers.class);
    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_ATTESTED_CREDENTIAL_DATA_INCLUDED = 0x40;
    private static final int FLAG_USER_VERIFIED = 0x04;
    private static final int FLAG_EXTENSION_DATA_INCLUDED = 0x80;

    @Autowired
    @Qualifier("base64UrlDecoder")
    private Base64.Decoder base64UrlDecoder;

    @Autowired
    @Qualifier("base64UrlEncoder")
    private Base64.Encoder base64UrlEncoder;

    @Autowired
    @Qualifier("base64Decoder")
    private Base64.Decoder base64Decoder;

    @Autowired
    @Qualifier("cborMapper")
    ObjectMapper cborMapper;
    @Autowired
    Provider provider;

    @Autowired
    List<AttestationFormatProcessor> supportedAttestationFormats;


    public void verifyU2FAttestationSignature(AuthData authData, byte[] clientDataHash, String signature, Certificate certificate, int signatureAlgorithm) {
        int bufferSize = 0;
        byte[] reserved = new byte[]{0x00};
        bufferSize += reserved.length;
        byte[] rpIdHash = authData.getRpIdHash();
        bufferSize += rpIdHash.length;

        bufferSize += clientDataHash.length;
        byte[] credId = authData.getCredId();
        bufferSize += credId.length;
        byte[] publicKey = convertCOSEtoPublicKey(authData.getCOSEPublicKey());
        bufferSize += publicKey.length;

        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(reserved).put(rpIdHash).put(clientDataHash).put(credId).put(publicKey).array();
        byte[] signatureBytes = base64Decoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        verifySignature(signatureBytes, signatureBase, certificate, signatureAlgorithm);
    }

    void verifyPackedAttestationSignature(AuthData authData, byte[] clientDataHash, String signature, Certificate certificate, int signatureAlgorithm) {
        int bufferSize = 0;
        byte[] rpIdHashBuffer = authData.getRpIdHash();
        bufferSize += rpIdHashBuffer.length;

        byte[] flagsBuffer = authData.getFlags();
        bufferSize += flagsBuffer.length;

        byte[] countersBuffer = authData.getCounters();
        bufferSize += countersBuffer.length;

        bufferSize += clientDataHash.length;
        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(rpIdHashBuffer).put(flagsBuffer).put(countersBuffer).put(clientDataHash).array();
        byte[] signatureBytes = base64Decoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        LOGGER.info("Signature BaseLen {}", signatureBase.length);
        verifySignature(signatureBytes, signatureBase, certificate, signatureAlgorithm);
    }

    public void verifyPackedAttestationSignature(byte[] authData, byte[] clientDataHash, String signature, PublicKey key, int signatureAlgorithm) {
        int bufferSize = 0;

        bufferSize += authData.length;
        bufferSize += clientDataHash.length;
        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(authData).put(clientDataHash).array();
        byte[] signatureBytes = base64Decoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        LOGGER.info("Signature BaseLen {}", signatureBase.length);
        verifySignature(signatureBytes, signatureBase, key, signatureAlgorithm);
    }

    public void verifyPackedAttestationSignature(byte[] authData, byte[] clientDataHash, String signature, Certificate certificate, int signatureAlgorithm) {
        verifyPackedAttestationSignature(authData, clientDataHash, signature, certificate.getPublicKey(), signatureAlgorithm);
    }

    public void verifyAssertionSignature(AuthData authData, byte[] clientDataHash, String signature, PublicKey publicKey, int signatureAlgorithm) {
        int bufferSize = 0;
        byte[] rpIdHash = authData.getRpIdHash();
        bufferSize += rpIdHash.length;
        byte[] flags= authData.getFlags();
        bufferSize += flags.length;
        byte[] counters= authData.getCounters();
        bufferSize += counters.length;
        bufferSize += clientDataHash.length;
        LOGGER.info("Client data hash HEX {}", Hex.encodeHexString(clientDataHash));
        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(rpIdHash).put(flags).put(counters).put(clientDataHash).array();
        byte[] signatureBytes = base64UrlDecoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        verifySignature(signatureBytes, signatureBase, publicKey, signatureAlgorithm);
    }

    public boolean verifyUserPresent(AuthData authData) {
        if ((authData.getFlags()[0] & FLAG_USER_PRESENT) == 1) {
            return true;
        } else {
            throw new Fido2RPRuntimeException("User not present");
        }
    }

    public boolean verifyUserVerified(AuthData authData) {
        if ((authData.getFlags()[0] & FLAG_USER_VERIFIED) == 1) {
            return true;
        } else {
            throw new Fido2RPRuntimeException("User not verified");
        }
    }

    public void verifyRpIdHash(AuthData authData, String domain) {
        try {
            byte[] retrievedRpIdHash = authData.getRpIdHash();
            byte[] calculatedRpIdHash = DigestUtils.getSha256Digest().digest(domain.getBytes("UTF-8"));
            LOGGER.debug("rpIDHash from Domain    HEX {}", Hex.encodeHexString(calculatedRpIdHash));
            LOGGER.debug("rpIDHash from Assertion HEX {}", Hex.encodeHexString(retrievedRpIdHash));
            if (!Arrays.equals(retrievedRpIdHash, calculatedRpIdHash)) {
                LOGGER.warn("hash from domain doesn't match hash from assertion HEX ");
                throw new Fido2RPRuntimeException("Hashes don't match");
            }
        } catch (UnsupportedEncodingException e) {
            throw new Fido2RPRuntimeException("This encoding is not supported" );
        }
    }


    public void verifyCounter(int oldCounter, int newCounter) {
        LOGGER.info("old counter {} new counter {} ", oldCounter, newCounter);
        if (newCounter == 0 && oldCounter == 0)
            return;
        if(newCounter <= oldCounter) {
            throw new Fido2RPRuntimeException("Counter did not increase");
        }
    }

    private byte[] convertCOSEtoPublicKey(byte[] cosePublicKey) {
        try {
            JsonNode cborPublicKey = cborMapper.readTree(cosePublicKey);
            byte[] x = base64Decoder.decode(cborPublicKey.get("-2").asText());
            byte[] y = base64Decoder.decode(cborPublicKey.get("-3").asText());
            byte[] keyBytes = ByteBuffer.allocate(1 + x.length + y.length).put((byte) 0x04).put(x).put(y).array();
            LOGGER.info("KeyBytes HEX {}", Hex.encodeHexString(keyBytes));
            return keyBytes;
        } catch (IOException e) {
            throw new Fido2RPRuntimeException("Can't parse public key");
        }
    }

    public void verifySignature(byte[] signature, byte[] signatureBase, Certificate certificate, int signatureAlgorithm) {
        verifySignature(signature, signatureBase, certificate.getPublicKey(), signatureAlgorithm);
    }

    private void verifySignature(byte[] signature, byte[] signatureBase, PublicKey publicKey, int signatureAlgorithm) {
        try {
            Signature signatureChecker = getSignatureChecker(signatureAlgorithm);
            signatureChecker.initVerify(publicKey);
            signatureChecker.update(signatureBase);
            if (!signatureChecker.verify(signature)) {
                throw new Fido2RPRuntimeException("Unable to verify signature");
            }
        } catch (IllegalArgumentException | InvalidKeyException | SignatureException e) {
            LOGGER.error("Can't verify the signature ", e);
            throw new Fido2RPRuntimeException("Can't verify the signature");
        }
    }

    private Signature getSignatureChecker(int signatureAlgorithm) {

        //https://www.iana.org/assignments/cose/cose.xhtml#algorithms
        try {

            switch (signatureAlgorithm) {
                case -7: {
                    Signature signatureChecker = Signature.getInstance("SHA256withECDSA", provider);
                    return signatureChecker;
                }

                case -35: {
                    Signature signatureChecker = Signature.getInstance("SHA384withECDSA", provider);
                    return signatureChecker;
                }

                case -36: {
                    Signature signatureChecker = Signature.getInstance("SHA512withECDSA", provider);
                    return signatureChecker;
                }


                case -37: {
                    Signature signatureChecker = Signature.getInstance("SHA256withRSA/PSS", provider);
                    signatureChecker.setParameter(new PSSParameterSpec("SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), 32, 1));
                    return signatureChecker;
                }
                case -38: {
                    Signature signatureChecker = Signature.getInstance("SHA384withRSA/PSS", provider);
                    signatureChecker.setParameter(new PSSParameterSpec("SHA-384", "MGF1", new MGF1ParameterSpec("SHA-384"), 32, 1));
                    return signatureChecker;
                }

                case -39: {
                    Signature signatureChecker = Signature.getInstance("SHA512withRSA/PSS", provider);
                    signatureChecker.setParameter(new PSSParameterSpec("SHA-512", "MGF1", new MGF1ParameterSpec("SHA-512"), 32, 1));
                    return signatureChecker;
                }
                case -257: {
                    Signature signatureChecker = Signature.getInstance("SHA256withRSA");
                    return signatureChecker;
                }
                case -258: {
                    Signature signatureChecker = Signature.getInstance("SHA384withRSA", provider);
                    return signatureChecker;
                }
                case -259: {
                    Signature signatureChecker = Signature.getInstance("SHA512withRSA", provider);
                    return signatureChecker;
                }
                case -65535: {
                    Signature signatureChecker = Signature.getInstance("SHA1withRSA", provider);
                    return signatureChecker;
                }


                default: {
                    throw new Fido2RPRuntimeException("Unknown mapping");
                }

            }

        } catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException e) {
            throw new Fido2RPRuntimeException("Problem with crypto");
        }
    }

    public MessageDigest getDigest(int signatureAlgorithm) {

        //https://www.iana.org/assignments/cose/cose.xhtml#algorithms
        switch (signatureAlgorithm) {
            case -257: {
                return DigestUtils.getSha256Digest();
            }

            case -65535: {
                return DigestUtils.getSha1Digest();
            }


            default: {
                throw new Fido2RPRuntimeException("Unknown mapping ");
            }

        }


    }

    public void verifyOptions(JsonNode params) {
        long count = Arrays.asList(
                params.hasNonNull("username")
//                params.hasNonNull("displayName")
//                params.hasNonNull("attestation")
                //params.hasNonNull("documentDomain")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid parameters");
        }
    }

    public void verifyBasicPayload(JsonNode params) {
        long count = Arrays.asList(
                params.hasNonNull("response"),
                params.hasNonNull("type")
                //params.hasNonNull("documentDomain")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid parameters");
        }
    }



    public String verifyBase64UrlString(JsonNode node) {
        String value = verifyThatString(node);
        try {
            base64UrlDecoder.decode(value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new Fido2RPRuntimeException("Invalid id");
        } catch (IllegalArgumentException e) {
            throw new Fido2RPRuntimeException("Invalid id");
        }

        return value;
    }


    public String verifyThatString(JsonNode node) {
        if (!node.isTextual()) {
            throw new Fido2RPRuntimeException("Invalid field " + node.fieldNames().next());
        }
        return node.asText();
    }

    public String verifyThatNonEmptyString(JsonNode node) {
        String value = verifyThatString(node);
        if (StringUtils.isEmpty(value)) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        } else {
            return value;
        }
    }

    public String verifyAuthData(JsonNode node) {
        if (node.isNull()) {
            throw new Fido2RPRuntimeException("Empty auth data");
        }

        String data = verifyThatBinary(node);
        if (data.isEmpty()) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        }
        return data;
    }

    public String verifyThatBinary(JsonNode node) {
        if (!node.isBinary()) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        }
        return node.asText();
    }

    public void verifyCounter(int counter) {
        if (counter < 0) {
            throw new Fido2RPRuntimeException("Invalid field : counter");
        }
    }

    public boolean verifyAtFlag(byte[] flags) {
        return (flags[0] & FLAG_ATTESTED_CREDENTIAL_DATA_INCLUDED) == FLAG_ATTESTED_CREDENTIAL_DATA_INCLUDED;
    }

    public void verifyAttestationBuffer(boolean hasAtFlag, byte[] attestationBuffer) {
        if (!hasAtFlag && attestationBuffer.length > 0) {
            throw new Fido2RPRuntimeException("Invalid attestation data buffer");
        }
        if (hasAtFlag && attestationBuffer.length == 0) {
            throw new Fido2RPRuntimeException("Invalid attestation data buffer");
        }
    }

    public void verifyNoLeftovers(byte[] leftovers) {
        if (leftovers.length > 0) {
            throw new Fido2RPRuntimeException("Invalid attestation data buffer: leftovers");
        }
    }

    public int verifyAlgorithm(JsonNode alg, int registeredAlgorithmType) {
        if (alg.isNull()) {
            throw new Fido2RPRuntimeException("Wrong algorithm");
        }
        int algorithmType = Integer.parseInt(alg.asText());
        if (algorithmType != registeredAlgorithmType) {
            throw new Fido2RPRuntimeException("Wrong algorithm");
        }
        return algorithmType;
    }

    public String verifyBase64String(JsonNode node) {
        if (node.isNull()) {
            throw new Fido2RPRuntimeException("Invalid data");
        }
        String value = verifyThatBinary(node);
        if (value.isEmpty()) {
            throw new Fido2RPRuntimeException("Invalid data");
        }
        try {
            base64Decoder.decode(value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new Fido2RPRuntimeException("Invalid data");
        } catch (IllegalArgumentException e) {
            throw new Fido2RPRuntimeException("Invalid data");
        }

        return value;

    }

    public void verifyPackedSurrogateAttestationSignature(byte[] authData, byte[] clientDataHash, String signature, PublicKey publicKey, int signatureAlgorithm) {
        int bufferSize = 0;
        bufferSize += authData.length;
        bufferSize += clientDataHash.length;
        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(authData).put(clientDataHash).array();
        byte[] signatureBytes = base64Decoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        LOGGER.info("Signature BaseLen {}", signatureBase.length);
        verifySignature(signatureBytes, signatureBase, publicKey, signatureAlgorithm);
    }

    public String verifyFmt(JsonNode fmtNode) {
        String fmt = verifyThatString(fmtNode);
        supportedAttestationFormats.stream().filter(f -> f.getAttestationFormat().getFmt().equals(fmt)).findAny().orElseThrow(() -> new Fido2RPRuntimeException("Unsupported attestation format " + fmt));
        return fmt;
    }

    public void verifyAAGUIDZeroed(AuthData authData) {
        byte[] buf = authData.getAaguid();
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] != 0) {
                throw new Fido2RPRuntimeException("Invalid AAGUID");
            }
        }
    }

    public void verifyTPMVersion(JsonNode ver) {
        if (!"2.0".equals(ver.asText())) {
            throw new Fido2RPRuntimeException("Invalid TPM Attestation version");
        }
    }

    public AttestationConveyancePreference verifyAttestationConveyanceType(JsonNode params) {
        if (params.has("attestation")) {
            String type = verifyThatString(params.get("attestation"));
            return AttestationConveyancePreference.valueOf(type);
        } else {
            return AttestationConveyancePreference.direct;
        }
    }

    public void verifyClientJSONTypeIsGet(JsonNode clientJsonNode) {
        verifyClientJSONType(clientJsonNode, "webauthn.get");
    }

    void verifyClientJSONType(JsonNode clientJsonNode, String type) {
        if (clientJsonNode.has("type")) {
            if (!type.equals(clientJsonNode.get("type").asText())) {
                throw new Fido2RPRuntimeException("Invalid client json parameters");
            }
        }
    }

    public void verifyClientJSONTypeIsCreate(JsonNode clientJsonNode) {
        verifyClientJSONType(clientJsonNode, "webauthn.create");
    }

    public void verifyClientJSON(JsonNode clientJsonNode) {
        long count = Arrays.asList(
                clientJsonNode.hasNonNull("challenge"),
                clientJsonNode.hasNonNull("origin"),
                clientJsonNode.hasNonNull("type")
                //params.hasNonNull("documentDomain")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid client json parameters");
        }
        verifyBase64UrlString(clientJsonNode.get("challenge"));


        if (clientJsonNode.hasNonNull("tokenBinding")) {
            verifyThatString(clientJsonNode.get("tokenBinding"));
        }

        String origin = verifyThatString(clientJsonNode.get("origin"));
        if (origin.isEmpty()) {
            throw new Fido2RPRuntimeException("Invalid client json parameters");
        }


    }


    public String verifyUserVerification(JsonNode userVerification) {
        try {
            return UserVerification.valueOf(userVerification.asText()).name();
        } catch (Exception e) {
            throw new Fido2RPRuntimeException("Wrong user verification parameter " + e.getMessage());
        }
    }


    public void verifyAttestationSignature(AuthData authData, byte[] clientDataHash, String signature, Certificate certificate, int signatureAlgorithm) {

        int bufferSize = 0;
        byte[] authDataBuffer = authData.getAttestationBuffer();
        bufferSize += authDataBuffer.length;
        bufferSize += clientDataHash.length;


        byte[] signatureBase = ByteBuffer.allocate(bufferSize).put(authDataBuffer).put(clientDataHash).array();
        byte[] signatureBytes = base64Decoder.decode(signature.getBytes());
        LOGGER.info("Signature {}", Hex.encodeHexString(signatureBytes));
        LOGGER.info("Signature Base {}", Hex.encodeHexString(signatureBase));
        verifySignature(signatureBytes, signatureBase, certificate, signatureAlgorithm);
    }

    public String verifyAssertionType(JsonNode typeNode) {
        String type = verifyThatString(typeNode);
        if (!"public-key".equals(type)) {
            throw new Fido2RPRuntimeException("Invalid type");
        }
        return type;
    }

    public void verifyRequiredUserPresent(AuthData authData) {
        LOGGER.info("required user present {}", Hex.encodeHexString(authData.getFlags()));
        byte flags = authData.getFlags()[0];

        if (!isUserPresent(flags) && !hasUserVerified(flags)) {
            throw new Fido2RPRuntimeException("User required not present");
        }
    }


    public void verifyPreferredUserPresent(AuthData authData) {

        LOGGER.info("preferred user present {}", Hex.encodeHexString(authData.getFlags()));
        byte flags = authData.getFlags()[0];
        if (isUserPresent(flags) && hasUserVerified(flags)) {
            throw new Fido2RPRuntimeException("User required not present");
        }
    }

    public void verifyDiscouragedUserPresent(AuthData authData) {
        LOGGER.info("discouraged user present {}", Hex.encodeHexString(authData.getFlags()));
        byte flags = authData.getFlags()[0];
        if (hasUserVerified(flags) && isUserPresent(flags)) {
            throw new Fido2RPRuntimeException("User discouraged is present present");
        }
    }

    private boolean hasUserVerified(byte flags) {
        boolean uv = (flags & FLAG_USER_VERIFIED) != 0;
        LOGGER.info("UV = {}", uv);
        return uv;

    }

    private boolean isUserPresent(byte flags) {
        boolean up = (flags & FLAG_USER_PRESENT) != 0;
        LOGGER.info("UP = {}", up);
        return up;
    }

    public void verifyThatMetadataIsValid(JsonNode metadata) {
        long count = Arrays.asList(
                metadata.hasNonNull("aaguid"),
                metadata.hasNonNull("assertionScheme"),
                metadata.hasNonNull("attestationTypes"),
                metadata.hasNonNull("description")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid parameters in metadata");
        }
    }
}


