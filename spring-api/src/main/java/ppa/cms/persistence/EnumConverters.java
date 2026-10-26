package ppa.cms.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import ppa.cms.domain.Reachability;
import ppa.cms.domain.Role;
import ppa.cms.domain.SystemEnvironment;

@Converter(autoApply = true)
class RoleConverter implements AttributeConverter<Role, String> {
    @Override
    public String convertToDatabaseColumn(Role role) {
        return role == null ? null : role.value();
    }

    @Override
    public Role convertToEntityAttribute(String value) {
        return Role.from(value);
    }
}

@Converter(autoApply = true)
class SystemEnvironmentConverter implements AttributeConverter<SystemEnvironment, String> {
    @Override
    public String convertToDatabaseColumn(SystemEnvironment environment) {
        return environment == null ? null : environment.value();
    }

    @Override
    public SystemEnvironment convertToEntityAttribute(String value) {
        return SystemEnvironment.from(value);
    }
}

@Converter(autoApply = true)
class ReachabilityConverter implements AttributeConverter<Reachability, String> {
    @Override
    public String convertToDatabaseColumn(Reachability status) {
        return status == null ? null : status.value();
    }

    @Override
    public Reachability convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        for (Reachability status : Reachability.values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        return null;
    }
}
