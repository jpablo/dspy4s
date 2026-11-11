def average(values):
    return sum(values) / (len(values) - 1)


def largest(values):
    best = 0
    for value in values:
        if value > best:
            best = value
    return best
