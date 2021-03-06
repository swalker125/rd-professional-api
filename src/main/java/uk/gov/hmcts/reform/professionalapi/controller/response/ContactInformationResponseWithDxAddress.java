package uk.gov.hmcts.reform.professionalapi.controller.response;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

import lombok.Getter;
import uk.gov.hmcts.reform.professionalapi.domain.ContactInformation;

@Getter
public class ContactInformationResponseWithDxAddress extends ContactInformationResponse {

    @JsonProperty
    private List<DxAddressResponse> dxAddress;

    public ContactInformationResponseWithDxAddress(ContactInformation contactInfo) {
        this.addressLine1 = contactInfo.getAddressLine1();
        this.addressLine2 = contactInfo.getAddressLine2();
        this.addressLine3 = contactInfo.getAddressLine3();
        this.townCity = contactInfo.getTownCity();
        this.county = contactInfo.getCounty();
        this.country = contactInfo.getCountry();
        this.postCode = contactInfo.getPostCode();
        this.dxAddress = contactInfo.getDxAddresses()
                .stream()
                .map(DxAddressResponse::new)
                .collect(toList());
    }

}
