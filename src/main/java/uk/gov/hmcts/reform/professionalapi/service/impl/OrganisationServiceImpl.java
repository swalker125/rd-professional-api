package uk.gov.hmcts.reform.professionalapi.service.impl;

import static uk.gov.hmcts.reform.professionalapi.controller.constants.ProfessionalApiConstants.LENGTH_OF_ORGANISATION_IDENTIFIER;
import static uk.gov.hmcts.reform.professionalapi.controller.constants.ProfessionalApiConstants.ONE;
import static uk.gov.hmcts.reform.professionalapi.controller.constants.ProfessionalApiConstants.ZERO_INDEX;
import static uk.gov.hmcts.reform.professionalapi.domain.OrganisationStatus.ACTIVE;
import static uk.gov.hmcts.reform.professionalapi.generator.ProfessionalApiGenerator.generateUniqueAlphanumericId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import org.springframework.util.StringUtils;
import uk.gov.hmcts.reform.professionalapi.controller.constants.IdamStatus;
import uk.gov.hmcts.reform.professionalapi.controller.constants.ProfessionalApiConstants;
import uk.gov.hmcts.reform.professionalapi.controller.feign.UserProfileFeignClient;
import uk.gov.hmcts.reform.professionalapi.controller.request.ContactInformationCreationRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.DeleteUserProfilesRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.DxAddressCreationRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.InvalidRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.OrganisationCreationRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.RetrieveUserProfilesRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.UserCreationRequest;
import uk.gov.hmcts.reform.professionalapi.controller.request.validator.PaymentAccountValidator;
import uk.gov.hmcts.reform.professionalapi.controller.response.DeleteOrganisationResponse;
import uk.gov.hmcts.reform.professionalapi.controller.response.NewUserResponse;
import uk.gov.hmcts.reform.professionalapi.controller.response.OrganisationEntityResponse;
import uk.gov.hmcts.reform.professionalapi.controller.response.OrganisationResponse;
import uk.gov.hmcts.reform.professionalapi.controller.response.OrganisationsDetailResponse;
import uk.gov.hmcts.reform.professionalapi.domain.ContactInformation;
import uk.gov.hmcts.reform.professionalapi.domain.DxAddress;
import uk.gov.hmcts.reform.professionalapi.domain.Organisation;
import uk.gov.hmcts.reform.professionalapi.domain.OrganisationStatus;
import uk.gov.hmcts.reform.professionalapi.domain.PaymentAccount;
import uk.gov.hmcts.reform.professionalapi.domain.ProfessionalUser;
import uk.gov.hmcts.reform.professionalapi.domain.UserAttribute;
import uk.gov.hmcts.reform.professionalapi.repository.ContactInformationRepository;
import uk.gov.hmcts.reform.professionalapi.repository.DxAddressRepository;
import uk.gov.hmcts.reform.professionalapi.repository.OrganisationRepository;
import uk.gov.hmcts.reform.professionalapi.repository.PaymentAccountRepository;
import uk.gov.hmcts.reform.professionalapi.repository.PrdEnumRepository;
import uk.gov.hmcts.reform.professionalapi.repository.ProfessionalUserRepository;
import uk.gov.hmcts.reform.professionalapi.service.OrganisationService;
import uk.gov.hmcts.reform.professionalapi.service.PrdEnumService;
import uk.gov.hmcts.reform.professionalapi.service.UserAccountMapService;
import uk.gov.hmcts.reform.professionalapi.service.UserAttributeService;
import uk.gov.hmcts.reform.professionalapi.util.RefDataUtil;


@Service
@Slf4j
@Setter
public class OrganisationServiceImpl implements OrganisationService {
    @Autowired
    OrganisationRepository organisationRepository;
    @Autowired
    ProfessionalUserRepository professionalUserRepository;
    @Autowired
    PaymentAccountRepository paymentAccountRepository;
    @Autowired
    DxAddressRepository dxAddressRepository;
    @Autowired
    ContactInformationRepository contactInformationRepository;
    @Autowired
    PrdEnumRepository prdEnumRepository;
    @Autowired
    UserAccountMapService userAccountMapService;
    @Autowired
    UserProfileFeignClient userProfileFeignClient;
    @Autowired
    PrdEnumService prdEnumService;
    @Autowired
    UserAttributeService userAttributeService;
    @Autowired
    PaymentAccountValidator paymentAccountValidator;

    @Value("${loggingComponentName}")
    private String loggingComponentName;

    @Override
    @Transactional
    public OrganisationResponse createOrganisationFrom(
            OrganisationCreationRequest organisationCreationRequest) {

        Organisation newOrganisation = new Organisation(
                RefDataUtil.removeEmptySpaces(organisationCreationRequest.getName()),
                OrganisationStatus.PENDING,
                RefDataUtil.removeEmptySpaces(organisationCreationRequest.getSraId()),
                RefDataUtil.removeEmptySpaces(organisationCreationRequest.getCompanyNumber()),
                Boolean.parseBoolean(RefDataUtil.removeEmptySpaces(organisationCreationRequest.getSraRegulated()
                        .toLowerCase())),
                RefDataUtil.removeAllSpaces(organisationCreationRequest.getCompanyUrl())
        );

        Organisation organisation = saveOrganisation(newOrganisation);

        addPbaAccountToOrganisation(organisationCreationRequest.getPaymentAccount(), organisation, false);

        addSuperUserToOrganisation(organisationCreationRequest.getSuperUser(), organisation);

        addContactInformationToOrganisation(organisationCreationRequest.getContactInformation(), organisation);

        return new OrganisationResponse(organisation);
    }

    public Organisation saveOrganisation(Organisation organisation) {
        Organisation persistedOrganisation = null;
        try {
            persistedOrganisation = organisationRepository.save(organisation);
        } catch (ConstraintViolationException ex) {
            organisation.setOrganisationIdentifier(generateUniqueAlphanumericId(LENGTH_OF_ORGANISATION_IDENTIFIER));
            persistedOrganisation = organisationRepository.save(organisation);
        }
        return persistedOrganisation;
    }

    public void addPbaAccountToOrganisation(Set<String> paymentAccounts,
                                            Organisation organisation, boolean pbasValidated) {
        if (paymentAccounts != null) {
            if (!pbasValidated) {
                PaymentAccountValidator.checkPbaNumberIsValid(paymentAccounts);
            }

            paymentAccounts.forEach(pbaAccount -> {
                PaymentAccount paymentAccount = new PaymentAccount(pbaAccount.toUpperCase());
                paymentAccount.setOrganisation(organisation);
                PaymentAccount persistedPaymentAccount = paymentAccountRepository.save(paymentAccount);
                organisation.addPaymentAccount(persistedPaymentAccount);
            });
        }
    }

    public void addSuperUserToOrganisation(
            UserCreationRequest userCreationRequest,
            Organisation organisation) {

        if (userCreationRequest.getEmail() == null) {
            throw new InvalidRequest("Email cannot be null");
        }
        ProfessionalUser newProfessionalUser = new ProfessionalUser(
                RefDataUtil.removeEmptySpaces(userCreationRequest.getFirstName()),
                RefDataUtil.removeEmptySpaces(userCreationRequest.getLastName()),
                RefDataUtil.removeAllSpaces(userCreationRequest.getEmail().toLowerCase()),
                organisation);


        ProfessionalUser persistedSuperUser = professionalUserRepository.save(newProfessionalUser);

        List<UserAttribute> attributes
                = userAttributeService.addUserAttributesToSuperUser(persistedSuperUser,
                newProfessionalUser.getUserAttributes());
        newProfessionalUser.setUserAttributes(attributes);

        userAccountMapService.persistedUserAccountMap(persistedSuperUser, organisation.getPaymentAccounts());

        organisation.addProfessionalUser(persistedSuperUser.toSuperUser());

    }

    public void addContactInformationToOrganisation(
            List<ContactInformationCreationRequest> contactInformationCreationRequest,
            Organisation organisation) {

        if (contactInformationCreationRequest != null) {
            contactInformationCreationRequest.forEach(contactInfo -> {
                ContactInformation newContactInformation = new ContactInformation();
                newContactInformation = setNewContactInformationFromRequest(newContactInformation, contactInfo,
                        organisation);

                ContactInformation contactInformation = contactInformationRepository.save(newContactInformation);

                addDxAddressToContactInformation(contactInfo.getDxAddress(), contactInformation);

            });
        }
    }

    public ContactInformation setNewContactInformationFromRequest(ContactInformation contactInformation,
                                                                  ContactInformationCreationRequest contactInfo,
                                                                  Organisation organisation) {
        contactInformation.setAddressLine1(RefDataUtil.removeEmptySpaces(contactInfo.getAddressLine1()));
        contactInformation.setAddressLine2(RefDataUtil.removeEmptySpaces(contactInfo.getAddressLine2()));
        contactInformation.setAddressLine3(RefDataUtil.removeEmptySpaces(contactInfo.getAddressLine3()));
        contactInformation.setTownCity(RefDataUtil.removeEmptySpaces(contactInfo.getTownCity()));
        contactInformation.setCounty(RefDataUtil.removeEmptySpaces(contactInfo.getCounty()));
        contactInformation.setCountry(RefDataUtil.removeEmptySpaces(contactInfo.getCountry()));
        contactInformation.setPostCode(RefDataUtil.removeEmptySpaces(contactInfo.getPostCode()));
        contactInformation.setOrganisation(organisation);
        return contactInformation;
    }

    private void addDxAddressToContactInformation(List<DxAddressCreationRequest> dxAddressCreationRequest,
                                                  ContactInformation contactInformation) {
        if (dxAddressCreationRequest != null) {
            List<DxAddress> dxAddresses = new ArrayList<>();
            dxAddressCreationRequest.forEach(dxAdd -> {
                DxAddress dxAddress = new DxAddress(
                        RefDataUtil.removeEmptySpaces(dxAdd.getDxNumber()),
                        RefDataUtil.removeEmptySpaces(dxAdd.getDxExchange()),
                        contactInformation);
                dxAddresses.add(dxAddress);
            });
            dxAddressRepository.saveAll(dxAddresses);
        }
    }

    public List<Organisation> retrieveActiveOrganisationDetails() {

        List<Organisation> updatedOrganisationDetails = new ArrayList<>();
        Map<String, Organisation> activeOrganisationDtls = new ConcurrentHashMap<>();

        List<Organisation> activeOrganisations = getOrganisationByStatus(ACTIVE);

        activeOrganisations.forEach(organisation -> {
            if (!organisation.getUsers().isEmpty() && null != organisation.getUsers().get(ZERO_INDEX)
                    .getUserIdentifier()) {
                activeOrganisationDtls.put(organisation.getUsers().get(ZERO_INDEX).getUserIdentifier(), organisation);
            }
        });

        if (!CollectionUtils.isEmpty(activeOrganisations)) {

            RetrieveUserProfilesRequest retrieveUserProfilesRequest
                    = new RetrieveUserProfilesRequest(activeOrganisationDtls.keySet().stream().sorted()
                    .collect(Collectors.toList()));
            updatedOrganisationDetails = RefDataUtil.getMultipleUserProfilesFromUp(userProfileFeignClient,
                    retrieveUserProfilesRequest,
                    "false", activeOrganisationDtls);

        }
        return updatedOrganisationDetails;
    }

    @Override
    public OrganisationsDetailResponse retrieveAllOrganisations() {
        List<Organisation> retrievedOrganisations = organisationRepository.findAll();

        if (retrievedOrganisations.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }

        List<Organisation> pendingOrganisations = new ArrayList<>();
        List<Organisation> activeOrganisations = new ArrayList<>();
        List<Organisation> resultingOrganisations = new ArrayList<>();

        Map<String, Organisation> activeOrganisationDetails = new ConcurrentHashMap<>();

        retrievedOrganisations.forEach(organisation -> {
            if (organisation.isOrganisationStatusActive()) {
                activeOrganisations.add(organisation);
                if (!organisation.getUsers().isEmpty() && null != organisation.getUsers().get(ZERO_INDEX)
                        .getUserIdentifier()) {
                    activeOrganisationDetails.put(organisation.getUsers().get(ZERO_INDEX).getUserIdentifier(),
                            organisation);
                }
            } else if (organisation.getStatus() == OrganisationStatus.PENDING) {
                pendingOrganisations.add(organisation);
            }
        });

        List<Organisation> updatedActiveOrganisations = new ArrayList<>();

        if (!CollectionUtils.isEmpty(activeOrganisations)) {

            RetrieveUserProfilesRequest retrieveUserProfilesRequest
                    = new RetrieveUserProfilesRequest(activeOrganisationDetails.keySet().stream().sorted()
                    .collect(Collectors.toList()));
            updatedActiveOrganisations = RefDataUtil.getMultipleUserProfilesFromUp(userProfileFeignClient,
                    retrieveUserProfilesRequest,
                    "false", activeOrganisationDetails);
        }

        resultingOrganisations.addAll(pendingOrganisations);
        resultingOrganisations.addAll(updatedActiveOrganisations);

        return new OrganisationsDetailResponse(resultingOrganisations, true);
    }

    @Override
    public OrganisationResponse updateOrganisation(
            OrganisationCreationRequest organisationCreationRequest, String organisationIdentifier) {

        Organisation organisation = organisationRepository.findByOrganisationIdentifier(organisationIdentifier);

        //Into update Organisation service
        organisation.setName(RefDataUtil.removeEmptySpaces(organisationCreationRequest.getName()));
        organisation.setStatus(OrganisationStatus.valueOf(organisationCreationRequest.getStatus()));
        organisation.setSraId(RefDataUtil.removeEmptySpaces(organisationCreationRequest.getSraId()));
        organisation.setCompanyNumber(RefDataUtil.removeEmptySpaces(organisationCreationRequest.getCompanyNumber()));
        organisation.setSraRegulated(Boolean.parseBoolean(RefDataUtil.removeEmptySpaces(organisationCreationRequest
                .getSraRegulated().toLowerCase())));
        organisation.setCompanyUrl(RefDataUtil.removeAllSpaces(organisationCreationRequest.getCompanyUrl()));
        organisationRepository.save(organisation);
        //Update Organisation service done

        return new OrganisationResponse(organisation);
    }

    @Override
    public Organisation getOrganisationByOrgIdentifier(String organisationIdentifier) {
        RefDataUtil.removeAllSpaces(organisationIdentifier);
        return organisationRepository.findByOrganisationIdentifier(organisationIdentifier);
    }

    @Override
    public OrganisationEntityResponse retrieveOrganisation(String organisationIdentifier) {
        Organisation organisation = organisationRepository.findByOrganisationIdentifier(organisationIdentifier);
        if (organisation == null) {
            throw new EmptyResultDataAccessException(ONE);

        } else if (ACTIVE.name().equalsIgnoreCase(organisation.getStatus().name())) {
            log.debug("{}:: Retrieving organisation", loggingComponentName);
            organisation.setUsers(RefDataUtil.getUserIdFromUserProfile(organisation.getUsers(), userProfileFeignClient,
                    false));
        }
        return new OrganisationEntityResponse(organisation, true);
    }

    @Override
    public OrganisationsDetailResponse findByOrganisationStatus(OrganisationStatus status) {

        List<Organisation> organisations = null;
        if (OrganisationStatus.PENDING.name().equalsIgnoreCase(status.name())) {

            organisations = getOrganisationByStatus(status);

        } else if (ACTIVE.name().equalsIgnoreCase(status.name())) {

            organisations = retrieveActiveOrganisationDetails();
        }

        if (CollectionUtils.isEmpty(organisations)) {
            throw new EmptyResultDataAccessException(ONE);

        }
        return new OrganisationsDetailResponse(organisations, true);
    }

    @Override
    @Transactional
    public DeleteOrganisationResponse deleteOrganisation(Organisation organisation, String prdAdminUserId) {
        DeleteOrganisationResponse deleteOrganisationResponse = new DeleteOrganisationResponse();
        switch (organisation.getStatus()) {
            case PENDING:
                return deleteOrganisationEntity(organisation, deleteOrganisationResponse, prdAdminUserId);
            case ACTIVE:
                deleteOrganisationResponse = deleteUserProfile(organisation, deleteOrganisationResponse);
                return deleteOrganisationResponse.getStatusCode() == ProfessionalApiConstants.STATUS_CODE_204
                        ? deleteOrganisationEntity(organisation, deleteOrganisationResponse, prdAdminUserId)
                        : deleteOrganisationResponse;
            default:
                throw new EmptyResultDataAccessException(ProfessionalApiConstants.ONE);
        }

    }

    private DeleteOrganisationResponse deleteOrganisationEntity(Organisation organisation,
                                                                DeleteOrganisationResponse deleteOrganisationResponse,
                                                                String prdAdminUserId) {
        organisationRepository.deleteById(organisation.getId());
        deleteOrganisationResponse.setStatusCode(ProfessionalApiConstants.STATUS_CODE_204);
        deleteOrganisationResponse.setMessage(ProfessionalApiConstants.DELETION_SUCCESS_MSG);
        log.info(loggingComponentName, organisation.getOrganisationIdentifier()
                + "::organisation deleted by::prdadmin::" + prdAdminUserId);
        return deleteOrganisationResponse;
    }

    private DeleteOrganisationResponse deleteUserProfile(Organisation organisation,
                                                         DeleteOrganisationResponse deleteOrganisationResponse) {

        // if user count more than one in the current organisation then throw exception
        if (ProfessionalApiConstants.USER_COUNT == professionalUserRepository
                .findByUserCountByOrganisationId(organisation.getId())) {
            ProfessionalUser user = organisation.getUsers()
                    .get(ProfessionalApiConstants.ZERO_INDEX).toProfessionalUser();
            NewUserResponse newUserResponse = RefDataUtil
                    .findUserProfileStatusByEmail(user.getEmailAddress(), userProfileFeignClient);

            if (StringUtils.isEmpty(newUserResponse.getIdamStatus())) {

                deleteOrganisationResponse.setStatusCode(ProfessionalApiConstants.ERROR_CODE_500);
                deleteOrganisationResponse.setMessage(ProfessionalApiConstants.ERR_MESG_500_ADMIN_NOTFOUNDUP);

            } else if (!IdamStatus.ACTIVE.name().equalsIgnoreCase(newUserResponse.getIdamStatus())) {
                // If user is not active in the up will send the request to delete
                Set<String> userIds = new HashSet<>();
                userIds.add(user.getUserIdentifier());
                DeleteUserProfilesRequest deleteUserRequest = new DeleteUserProfilesRequest(userIds);
                deleteOrganisationResponse = RefDataUtil
                        .deleteUserProfilesFromUp(deleteUserRequest, userProfileFeignClient);
            } else {
                deleteOrganisationResponse.setStatusCode(ProfessionalApiConstants.ERROR_CODE_400);
                deleteOrganisationResponse.setMessage(ProfessionalApiConstants.ERROR_MESSAGE_400_ADMIN_NOT_PENDING);
            }
        } else {
            deleteOrganisationResponse.setStatusCode(ProfessionalApiConstants.ERROR_CODE_400);
            deleteOrganisationResponse.setMessage(ProfessionalApiConstants.ERROR_MESSAGE_400_ORG_MORE_THAN_ONE_USER);
        }
        return deleteOrganisationResponse;
    }

    public List<Organisation> getOrganisationByStatus(OrganisationStatus status) {
        return organisationRepository.findByStatus(status);
    }

}

