// ABAC rules: each resource defines actions, each action is a function
// that receives the user's attributes and returns true/false.
// Add more resources and actions as the app grows.

const rules = {
    user: {
        read:   ({ role }) => ['admin', 'manager', 'user'].includes(role),
        write:  ({ role }) => ['admin', 'manager'].includes(role),
        delete: ({ role }) => role === 'admin',
    },
    report: {
        read:   ({ role }) => ['admin', 'manager'].includes(role),
        write:  ({ role }) => role === 'admin',
        delete: ({ role }) => role === 'admin',
    },
};

const policy = (user, action, resource) => {
    if (!user) return false;

    // Look up the rules for the requested resource (e.g. 'user', 'report')
    const resourceRules = rules[resource];
    if (!resourceRules) return false;

    // Look up the rule function for the requested action (e.g. 'read', 'delete')
    const check = resourceRules[action];
    if (!check) return false;

    // Call the rule function with the user object.
    // It destructures { role } from user and returns true/false.
    // e.g. check({ id: '123', role: 'manager' }) → true/false
    return check(user);
};

module.exports = policy;
